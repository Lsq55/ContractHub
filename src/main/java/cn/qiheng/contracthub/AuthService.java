package cn.qiheng.contracthub;

import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static cn.qiheng.contracthub.Db.*;
import static cn.qiheng.contracthub.ApiException.require;

@Service
public class AuthService {
    /** 未知账号时用于对齐耗时的固定哈希，避免用响应时间枚举账号。 */
    static final String DUMMY_HASH="$argon2id$v=19$m=16384,t=2,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    /** 按业务要求最低 6 位；仍拦截常见弱密码。 */
    static final int MIN_PASSWORD_LENGTH=6;
    static final int RATE_WINDOW_SECONDS=900;
    final Db db;
    final Argon2PasswordEncoder encoder=Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    final boolean secure,trustForwarded;
    final int accountLimit,sourceLimit,reviewTtlMinutes;
    AuthService(Db db,@Value("${app.secure-cookie}") boolean secure,@Value("${app.login-account-limit}") int accountLimit,
                @Value("${app.login-source-limit}") int sourceLimit,@Value("${app.trust-forwarded-header}") boolean trustForwarded,
                @Value("${app.review-ttl-minutes}") int reviewTtlMinutes) {
        this.db=db; this.secure=secure; this.accountLimit=accountLimit; this.sourceLimit=sourceLimit;
        this.trustForwarded=trustForwarded; this.reviewTtlMinutes=reviewTtlMinutes;
    }
    static String random() { byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    static String cookie(HttpServletRequest r,String name) {
        if(r.getCookies()!=null) for(Cookie c:r.getCookies()) if(c.getName().equals(name)) return c.getValue();
        return "";
    }
    void setCookie(HttpServletResponse r,String name,String value,boolean httpOnly,long seconds) {
        r.addHeader("Set-Cookie",ResponseCookie.from(name,value).httpOnly(httpOnly).secure(secure).sameSite("Strict").path("/").maxAge(seconds).build().toString());
    }
    /** 仅当应用前存在可信反向代理时才采信 X-Forwarded-For，否则客户端可伪造来源绕过限速。 */
    String source(HttpServletRequest req) {
        if(trustForwarded) {
            String header=req.getHeader("X-Forwarded-For");
            if(header!=null&&!header.isBlank()) return header.split(",")[0].trim();
        }
        return Objects.toString(req.getRemoteAddr(),"unknown");
    }
    boolean matches(String raw,String hash) {
        if(raw==null||hash==null||hash.isBlank()) return false;
        try { return encoder.matches(raw,hash); } catch(IllegalArgumentException e) { return false; }
    }
    Map<String,Object> authenticate(HttpServletRequest r) {
        String token=cookie(r,"QH_SESSION");
        require(!token.isEmpty(),401,"UNAUTHENTICATED","请先登录");
        var row=db.find("SELECT u.*,s.csrf_token,s.id AS session_id FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.id=? AND u.enabled=TRUE AND u.deleted_at IS NULL AND s.session_epoch=u.session_epoch AND s.expires_at>? AND s.last_seen_at>?",hash(token),at(Instant.now()),at(Instant.now().minusSeconds(1800)));
        require(row!=null,401,"SESSION_EXPIRED","登录已过期或账号权限已变更，请重新登录");
        db.update("UPDATE sessions SET last_seen_at=? WHERE id=?",at(Instant.now()),row.get("session_id"));
        return row;
    }
    @SuppressWarnings("unchecked") Map<String,Object> actor(HttpServletRequest r) { return (Map<String,Object>)r.getAttribute("actor"); }
    Map<String,Object> safe(Map<String,Object> u) {
        var out=new LinkedHashMap<String,Object>();
        for(String k:List.of("id","username","display_name","role","enabled","must_change_password","created_at")) out.put(k,u.get(k));
        return out;
    }
    void role(Map<String,Object> u,String... roles) { require(u!=null&&Arrays.asList(roles).contains(str(u,"role")),403,"FORBIDDEN","当前账号没有此操作权限"); }
    <T> T write(HttpServletRequest r,Supplier<T> work,String... roles) {
        return db.locked(()->{
            var before=actor(r);
            var current=db.one("SELECT * FROM users WHERE id=?",before.get("id"));
            require(Boolean.TRUE.equals(current.get("enabled"))&&num(current,"session_epoch")==num(before,"session_epoch"),401,"SESSION_REVOKED","账号权限已变更，请重新登录");
            if(roles.length>0) role(current,roles);
            return work.get();
        });
    }
    void password(String value) {
        require(value!=null&&value.length()>=MIN_PASSWORD_LENGTH&&value.length()<=128,422,"WEAK_PASSWORD","密码须为 "+MIN_PASSWORD_LENGTH+"～128 位");
        require(!WEAK.contains(value.toLowerCase(Locale.ROOT)),422,"WEAK_PASSWORD","密码过于常见，请更换为随机强密码");
    }
    private static final Set<String> WEAK=Set.of("123456","12345678","123456789","1234567890","123456789012","password","password1","password123",
            "admin123","administrator","qwerty","qwertyuiop","111111111111","888888888888","abc123456789","iloveyou","letmein12345","welcome12345",
            "qiheng123456","contract1234");
    List<String> buckets(String username,String source) { return List.of("account:"+hash(username.toLowerCase(Locale.ROOT)),"source:"+hash(source)); }
    /** 返回被限速的桶维度，未触发时返回 null。 */
    String blocked(List<String> buckets) {
        for(int i=0;i<buckets.size();i++) {
            var attempt=db.find("SELECT * FROM login_attempts WHERE bucket=?",buckets.get(i));
            if(attempt==null) continue;
            if(Instant.parse(str(attempt,"window_start")).isAfter(Instant.now().minusSeconds(RATE_WINDOW_SECONDS))&&num(attempt,"failures")>=(i==0?accountLimit:sourceLimit)) return i==0?"account":"source";
        }
        return null;
    }
    void countFailure(List<String> buckets) {
        for(int i=0;i<buckets.size();i++) {
            String bucket=buckets.get(i);
            var a=db.find("SELECT * FROM login_attempts WHERE bucket=?",bucket);
            if(a==null) db.update("INSERT INTO login_attempts(bucket,failures,window_start) VALUES (?,1,?)",bucket,at(Instant.now()));
            else if(Instant.parse(str(a,"window_start")).isBefore(Instant.now().minusSeconds(RATE_WINDOW_SECONDS))) db.update("UPDATE login_attempts SET failures=1,window_start=? WHERE bucket=?",at(Instant.now()),bucket);
            else db.update("UPDATE login_attempts SET failures=failures+1 WHERE bucket=?",bucket);
        }
    }
    Map<String,Object> login(Map<String,Object> b,HttpServletRequest req,HttpServletResponse res) {
        String username=required(b,"username",64), pass=required(b,"password",128);
        var buckets=buckets(username,source(req));
        // 1) 只把廉价查询放进全局锁：Argon2 校验移出锁，避免登录互相拖慢整个系统的写操作。
        var gate=db.locked(()->{
            if(blocked(buckets)!=null) return Map.<String,Object>of("failure",429);
            var u=db.find("SELECT * FROM users WHERE LOWER(username)=LOWER(?)",username);
            return u==null?Map.<String,Object>of("missing",true):new LinkedHashMap<>(u);
        });
        if("429".equals(str(gate,"failure"))) throw new ApiException(429,"RATE_LIMITED","尝试次数过多，请 15 分钟后重试");
        Map<String,Object> user=Boolean.TRUE.equals(gate.get("missing"))?null:gate;
        // 2) 密码校验在锁外执行（未知账号也做一次等价哈希，保持耗时一致）。
        boolean valid=user!=null&&matches(pass,str(user,"password_hash"));
        if(user==null) matches(pass,DUMMY_HASH);
        if(!valid||!Boolean.TRUE.equals(user.get("enabled"))) {
            db.locked(()->{ countFailure(buckets); db.audit(null,"LOGIN_FAILED","AUTH",null,Map.of("account_hash",hash(username.toLowerCase(Locale.ROOT)))); return null; });
            throw new ApiException(401,"INVALID_CREDENTIALS","账号或密码错误，或账号已停用");
        }
        // 3) 成功：两个维度一起清零（原先只清账户桶，同一 IP 上的其他人会被长期连带锁定）。
        db.locked(()->{
            for(String bucket:buckets) db.update("DELETE FROM login_attempts WHERE bucket=?",bucket);
            session(user,res);
            db.audit(str(user,"id"),"LOGIN","AUTH",str(user,"id"),Map.of());
            return null;
        });
        return safe(user);
    }
    void session(Map<String,Object> u,HttpServletResponse res) {
        String token=random(),csrf=random();
        db.update("INSERT INTO sessions(id,user_id,session_epoch,csrf_token,expires_at,last_seen_at) VALUES (?,?,?,?,?,?)",hash(token),u.get("id"),u.get("session_epoch"),csrf,at(Instant.now().plusSeconds(28800)),at(Instant.now()));
        setCookie(res,"QH_SESSION",token,true,28800); setCookie(res,"QH_CSRF",csrf,false,28800);
    }
    void bootstrap(String username,String name,String pass,boolean recover) {
        password(pass);
        require(username.matches("[a-zA-Z0-9_.-]{3,64}"),422,"INVALID_USERNAME","账号格式无效");
        String encoded=encoder.encode(pass);
        db.locked(()->{
            if(!recover) require(db.count("SELECT COUNT(*) FROM users")==0,409,"ALREADY_INITIALIZED","系统已经初始化，不能重复创建首个管理员");
            var u=db.find("SELECT * FROM users WHERE LOWER(username)=LOWER(?)",username);
            String uid=u==null?id():str(u,"id");
            if(u==null) db.update("INSERT INTO users(id,username,display_name,password_hash,role) VALUES (?,?,?,?,'ADMIN')",uid,username,name,encoded);
            else { db.update("UPDATE users SET password_hash=?,role='ADMIN',enabled=TRUE,must_change_password=TRUE,deleted_at=NULL,deleted_by=NULL,delete_reason=NULL,session_epoch=session_epoch+1 WHERE id=?",encoded,uid); db.update("DELETE FROM sessions WHERE user_id=?",uid); }
            db.audit(uid,recover?"ADMIN_RECOVERED":"ADMIN_INITIALIZED","USER",uid,Map.of("channel","local-command"));
            return null;
        });
    }
}
