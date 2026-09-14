package cn.qiheng.contracthub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

@Component
public class Db {
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    final ObjectMapper json;
    public Db(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) { this.jdbc=jdbc; this.tx=tx; this.json=json; }
    public List<Map<String,Object>> list(String sql, Object... args) { return jdbc.queryForList(sql,args).stream().map(this::decode).toList(); }
    public Map<String,Object> one(String sql, Object... args) {
        var rows=list(sql,args);
        if(rows.isEmpty()) throw new ApiException(404,"NOT_FOUND","记录不存在或无权访问");
        return rows.getFirst();
    }
    public Map<String,Object> find(String sql, Object... args) { var rows=list(sql,args); return rows.isEmpty()?null:rows.getFirst(); }
    public int update(String sql,Object... args) { return jdbc.update(sql,args); }
    public long count(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    public <T> T locked(Supplier<T> work) { return tx.execute(status -> { jdbc.queryForObject("SELECT id FROM system_guard WHERE id=1 FOR UPDATE",Integer.class); return work.get(); }); }
    public String stringify(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("JSON 无效",e); } }
    public Map<String,Object> parse(String value) { try { return json.readValue(value,new TypeReference<>(){}); } catch(Exception e) { throw new ApiException(422,"INVALID_JSON","JSON 配置格式不正确"); } }
    private Map<String,Object> decode(Map<String,Object> row) {
        var out=new LinkedHashMap<String,Object>();
        row.forEach((k,v)->{
            if(v instanceof String s && Set.of("field_schema","form_data","data_snapshot","detail","migration_map","response_json").contains(k)) v=parse(s);
            if(v instanceof Timestamp t) v=t.toInstant().toString();
            if(v instanceof java.sql.Date d) v=d.toLocalDate().toString();
            out.put(k,v);
        }); return out;
    }
    public void audit(String actor,String action,String type,String id,Object detail) {
        update("INSERT INTO audit_logs(id,actor_id,action,object_type,object_id,request_id,detail) VALUES (?,?,?,?,?,?,?)",id(),actor,action,type,id,RequestContext.requestId(),stringify(detail));
    }
    /** 记录"用户确实打开过这份 PDF"。用 UPDATE+INSERT 实现，H2 与 PostgreSQL 都可用（H2 的 MERGE ... KEY 在 PG 上是语法错误）。 */
    public void recordPreviewView(String userId,String jobId,String sha256) {
        Timestamp now=at(Instant.now());
        if(update("UPDATE preview_views SET pdf_sha256=?,viewed_at=? WHERE user_id=? AND job_id=?",sha256,now,userId,jobId)==0) {
            try { update("INSERT INTO preview_views(user_id,job_id,pdf_sha256,viewed_at) VALUES (?,?,?,?)",userId,jobId,sha256,now); }
            catch(org.springframework.dao.DataIntegrityViolationException e) { update("UPDATE preview_views SET pdf_sha256=?,viewed_at=? WHERE user_id=? AND job_id=?",sha256,now,userId,jobId); }
        }
    }
    static String id() { return UUID.randomUUID().toString(); }
    static String str(Map<String,Object> m,String k) { return Objects.toString(m.get(k),""); }
    static int num(Map<String,Object> m,String k) { return ((Number)m.getOrDefault(k,0)).intValue(); }
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Map<String,Object> m,String k) { return m.get(k) instanceof Map<?,?> ? (Map<String,Object>)m.get(k) : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> fields(Map<String,Object> s) { return (List<Map<String,Object>>)s.getOrDefault("fields",List.of()); }
    static String hash(String s) { return hash(s.getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(Exception e) { throw new IllegalStateException(e); } }
    static Timestamp at(Instant i) { return Timestamp.from(i); }
    static String required(Map<String,Object> b,String key,int max) { String v=str(b,key).trim(); ApiException.require(!v.isEmpty()&&v.length()<=max,422,"INVALID_INPUT",key+" 不能为空且不能超过 "+max+" 字符"); return v; }
    static void lockVersion(Map<String,Object> row,Map<String,Object> body) {
        ApiException.require(body.get("expected_lock_version") instanceof Number && num(row,"lock_version")==num(body,"expected_lock_version"),409,"EDIT_CONFLICT","内容已被其他窗口修改，请保留当前输入并刷新后重试");
    }
}
