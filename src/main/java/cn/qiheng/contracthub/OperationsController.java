package cn.qiheng.contracthub;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static cn.qiheng.contracthub.Db.*;
import static cn.qiheng.contracthub.ApiException.require;

@RestController @RequestMapping("/api/v1")
class OperationsController {
 final Db db; final AuthService auth; final FileStore files; final GenerationService generation;
 OperationsController(Db db,AuthService auth,FileStore files,GenerationService generation){this.db=db;this.auth=auth;this.files=files;this.generation=generation;}
 /** 支持按操作人/动作/对象/日期筛选与分页，避免审计页只能看到最新若干条。 */
 @GetMapping("/audit-logs") Object audit(HttpServletRequest r,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String actor,@RequestParam(defaultValue="") String action,@RequestParam(defaultValue="") String objectType,@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size){
  auth.role(auth.actor(r),"ADMIN"); size=Math.max(1,Math.min(size,200)); page=Math.max(1,Math.min(page,1_000_000));
  String term="%"+q.toLowerCase(Locale.ROOT)+"%", who="%"+actor.toLowerCase(Locale.ROOT)+"%";
  Timestamp start=startOf(from), end=endOf(to);
  String where=" WHERE (?='' OR LOWER(a.action||' '||a.object_type||' '||COALESCE(a.object_id,'')||' '||a.request_id) LIKE ?)"+
   " AND (?='' OR LOWER(COALESCE(u.username,'')||' '||COALESCE(u.display_name,'')) LIKE ?)"+
   " AND (?='' OR a.action=?) AND (?='' OR a.object_type=?) AND a.created_at>=? AND a.created_at<=?";
  Object[] base={q,term,actor,who,action,action,objectType,objectType,start,end};
  var items=db.list("SELECT a.*,u.username FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_id"+where+" ORDER BY a.created_at DESC LIMIT ? OFFSET ?",concat(base,size,(page-1)*size));
  long total=db.count("SELECT COUNT(*) FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_id"+where,base);
  var out=new LinkedHashMap<String,Object>(); out.put("items",items); out.put("total",total); out.put("page",page); out.put("size",size); return out;
 }
 static Object[] concat(Object[] base,Object... tail){ Object[] all=Arrays.copyOf(base,base.length+tail.length); System.arraycopy(tail,0,all,base.length,tail.length); return all; }
 Timestamp startOf(String text){
  if(text==null||text.isBlank()) return at(Instant.EPOCH);
  try { return at(Instant.parse(text)); } catch(Exception ignored) { }
  try { return at(LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant()); } catch(Exception e) { throw new ApiException(422,"INVALID_DATE","日期格式应为 YYYY-MM-DD"); }
 }
 Timestamp endOf(String text){
  if(text==null||text.isBlank()) return at(Instant.parse("9999-12-31T00:00:00Z"));
  try { return at(Instant.parse(text)); } catch(Exception ignored) { }
  try { return at(LocalDate.parse(text).plusDays(1).atStartOfDay(ZoneId.systemDefault()).minusNanos(1).toInstant()); } catch(Exception e) { throw new ApiException(422,"INVALID_DATE","日期格式应为 YYYY-MM-DD"); }
 }
 @GetMapping("/system/status") Object status(HttpServletRequest r){
  auth.role(auth.actor(r),"ADMIN"); long free=0; try { free=Files.getFileStore(files.root).getUsableSpace(); } catch(Exception ignored) { }
  var lastSucceeded=db.find("SELECT MAX(finished_at) AS finished FROM generation_jobs WHERE state='SUCCEEDED'");
  var result=new LinkedHashMap<String,Object>();
  result.put("database","UP");
  result.put("storage_free_bytes",free);
  result.put("queued_jobs",db.count("SELECT COUNT(*) FROM generation_jobs WHERE state='QUEUED'"));
  result.put("running_jobs",db.count("SELECT COUNT(*) FROM generation_jobs WHERE state='RUNNING'"));
  result.put("succeeded_jobs",db.count("SELECT COUNT(*) FROM generation_jobs WHERE state='SUCCEEDED'"));
  result.put("failed_jobs",db.count("SELECT COUNT(*) FROM generation_jobs WHERE state='FAILED'"));
  result.put("converter_available",generation.converterAvailable());
  result.put("worker_concurrency",generation.concurrency);
  result.put("last_succeeded_at",lastSucceeded==null?null:lastSucceeded.get("finished"));
  return result;
 }
}
