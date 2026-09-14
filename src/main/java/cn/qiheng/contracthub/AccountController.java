package cn.qiheng.contracthub;

import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.qiheng.contracthub.Db.*;
import static cn.qiheng.contracthub.ApiException.require;

@RestController
@RequestMapping("/api/v1")
public class AccountController {
    final Db db; final AuthService auth;
    AccountController(Db db,AuthService auth) { this.db=db; this.auth=auth; }
    @GetMapping("/health") Object health() { db.count("SELECT COUNT(*) FROM system_guard"); return Map.of("status","UP","name","契衡合同管理系统"); }
    @GetMapping("/auth/csrf") Object csrf(HttpServletRequest req,HttpServletResponse res) {
        String csrf=AuthService.random();
        if(!AuthService.cookie(req,"QH_SESSION").isEmpty()) {
            try { csrf=str(auth.authenticate(req),"csrf_token"); }
            catch(ApiException e) { if(e.status!=401) throw e; }
        }
        auth.setCookie(res,"QH_CSRF",csrf,false,28800);
        return Map.of("csrf_token",csrf);
    }
    @PostMapping("/auth/login") Object login(@RequestBody Map<String,Object> b,HttpServletRequest r,HttpServletResponse s) { return auth.login(b,r,s); }
    @GetMapping("/auth/me") Object me(HttpServletRequest r) { return auth.safe(auth.actor(r)); }
    @PostMapping("/auth/logout") Object logout(HttpServletRequest r,HttpServletResponse s) { return auth.write(r,()->{ db.update("DELETE FROM sessions WHERE id=?",auth.actor(r).get("session_id")); auth.setCookie(s,"QH_SESSION","",true,0); auth.setCookie(s,"QH_CSRF","",false,0); return Map.of("ok",true); }); }
    @PostMapping("/auth/change-password") Object change(@RequestBody Map<String,Object> b,HttpServletRequest r,HttpServletResponse s) {
        var uid=auth.actor(r).get("id");
        var u=db.one("SELECT * FROM users WHERE id=?",uid);
        String old=str(b,"old_password"), pass=required(b,"new_password",128);
        require(auth.matches(old,str(u,"password_hash")),422,"WRONG_PASSWORD","当前密码不正确");
        auth.password(pass);
        require(!pass.equals(old),422,"SAME_PASSWORD","新密码不能与原密码相同");
        // Argon2 编码在全局锁之外完成，缩短锁持有时间。
        String encoded=auth.encoder.encode(pass);
        return auth.write(r,()->{
            db.update("UPDATE users SET password_hash=?,must_change_password=FALSE,session_epoch=session_epoch+1 WHERE id=?",encoded,u.get("id"));
            db.update("DELETE FROM sessions WHERE user_id=?",u.get("id"));
            var fresh=db.one("SELECT * FROM users WHERE id=?",u.get("id")); auth.session(fresh,s);
            db.audit(str(u,"id"),"PASSWORD_CHANGED","USER",str(u,"id"),Map.of()); return auth.safe(fresh);
        });
    }
    @GetMapping("/users") Object users(HttpServletRequest r) { auth.role(auth.actor(r),"ADMIN"); return db.list("SELECT id,username,display_name,role,enabled,must_change_password,created_at FROM users WHERE deleted_at IS NULL ORDER BY created_at"); }
    @PostMapping("/users") Object create(@RequestBody Map<String,Object> b,HttpServletRequest r) {
        String username=required(b,"username",64),name=required(b,"display_name",100),role=required(b,"role",32);
        require(username.matches("[a-zA-Z0-9_.-]{3,64}"),422,"INVALID_USERNAME","账号仅支持 3～64 位字母、数字、点、横线和下划线");
        require(Set.of("USER","CONTRACT_MAINTAINER","ADMIN").contains(role),422,"INVALID_ROLE","未知用户类别");
        String raw=str(b,"password"); String pass=raw.isBlank()?AuthService.random().substring(0,20):raw; auth.password(pass);
        String encoded=auth.encoder.encode(pass);
        final String temporary=pass;
        return auth.write(r,()->{
            require(db.count("SELECT COUNT(*) FROM users WHERE LOWER(username)=LOWER(?)",username)==0,409,"USERNAME_TAKEN","账号已存在（含已停用账号占用的名称）");
            String id=id(); db.update("INSERT INTO users(id,username,display_name,password_hash,role) VALUES (?,?,?,?,?)",id,username,name,encoded,role);
            db.audit(str(auth.actor(r),"id"),"USER_CREATED","USER",id,Map.of("role",role));
            return Map.of("user",auth.safe(db.one("SELECT * FROM users WHERE id=?",id)),"temporary_password",temporary);
        },"ADMIN");
    }
    @PatchMapping("/users/{id}") Object patch(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var u=db.one("SELECT * FROM users WHERE id=? AND deleted_at IS NULL",id); String role=b.containsKey("role")?str(b,"role"):str(u,"role");
        require(Set.of("USER","CONTRACT_MAINTAINER","ADMIN").contains(role),422,"INVALID_ROLE","未知用户类别");
        require(!b.containsKey("enabled")||b.get("enabled") instanceof Boolean,422,"INVALID_INPUT","启用状态必须为布尔值");
        boolean enabled=(boolean)b.getOrDefault("enabled",u.get("enabled"));
        if(str(u,"role").equals("ADMIN")&&Boolean.TRUE.equals(u.get("enabled"))&&(!role.equals("ADMIN")||!enabled)) require(db.count("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND enabled=TRUE")>1,409,"LAST_ADMIN","必须保留至少一位启用的管理员");
        String name=b.containsKey("display_name")?required(b,"display_name",100):str(u,"display_name");
        boolean permissionsChanged=!role.equals(str(u,"role"))||enabled!=Boolean.TRUE.equals(u.get("enabled"));
        // 只有权限/启停变化才撤销会话；仅改姓名不应把用户踢下线。
        db.update("UPDATE users SET role=?,enabled=?,display_name=?"+(permissionsChanged?",session_epoch=session_epoch+1":"")+" WHERE id=?",role,enabled,name,id);
        if(permissionsChanged) db.update("DELETE FROM sessions WHERE user_id=?",id);
        db.audit(str(auth.actor(r),"id"),"USER_UPDATED","USER",id,Map.of("before",Map.of("role",u.get("role"),"enabled",u.get("enabled")),"after",Map.of("role",role,"enabled",enabled)));
        return auth.safe(db.one("SELECT * FROM users WHERE id=?",id));
    },"ADMIN"); }
    @PostMapping("/users/{id}/reset-password") Object reset(@PathVariable String id,HttpServletRequest r) {
        db.one("SELECT id FROM users WHERE id=? AND deleted_at IS NULL",id);
        String pass=AuthService.random().substring(0,20);
        String encoded=auth.encoder.encode(pass);
        return auth.write(r,()->{
            db.update("UPDATE users SET password_hash=?,must_change_password=TRUE,session_epoch=session_epoch+1 WHERE id=?",encoded,id); db.update("DELETE FROM sessions WHERE user_id=?",id);
            db.audit(str(auth.actor(r),"id"),"PASSWORD_RESET","USER",id,Map.of()); return Map.of("temporary_password",pass);
        },"ADMIN");
    }
    @DeleteMapping("/users/{id}") Object delete(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var u=db.one("SELECT * FROM users WHERE id=? AND deleted_at IS NULL",id); String reason=required(b,"reason",1000);
        if(str(u,"role").equals("ADMIN")&&Boolean.TRUE.equals(u.get("enabled"))) require(db.count("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND enabled=TRUE AND deleted_at IS NULL")>1,409,"LAST_ADMIN","必须保留至少一位启用的管理员");
        db.update("UPDATE users SET enabled=FALSE,deleted_at=?,deleted_by=?,delete_reason=?,session_epoch=session_epoch+1 WHERE id=?",at(java.time.Instant.now()),auth.actor(r).get("id"),reason,id); db.update("DELETE FROM sessions WHERE user_id=?",id);
        db.audit(str(auth.actor(r),"id"),"USER_DELETED","USER",id,Map.of("reason",reason,"owned_contracts",db.count("SELECT COUNT(*) FROM contracts WHERE owner_id=? AND deleted_at IS NULL",id))); return Map.of("ok",true);
    },"ADMIN"); }
}
