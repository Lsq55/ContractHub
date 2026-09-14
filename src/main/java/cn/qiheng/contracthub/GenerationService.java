package cn.qiheng.contracthub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static cn.qiheng.contracthub.Db.*;
import static cn.qiheng.contracthub.ApiException.require;

@Service
public class GenerationService {
    final Db db; final FileStore files; final FieldRules rules; final DocumentEngine engine;
    final String executable,profile; final int timeout,concurrency; final boolean enabled,cleanupEnabled; final int orphanRetentionHours;
    /** jobId -> 当前持有该任务的 lease_token，避免同一任务被重复计数或在租约切换后错误释放名额。 */
    final Map<String,String> active=new ConcurrentHashMap<>();
    final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    GenerationService(Db db,FileStore files,FieldRules rules,DocumentEngine engine,@Value("${app.libreoffice}") String executable,@Value("${app.render-profile}") String profile,@Value("${app.convert-timeout}") int timeout,@Value("${app.worker-enabled}") boolean enabled,@Value("${app.worker-concurrency}") int concurrency,@Value("${app.cleanup-enabled}") boolean cleanupEnabled,@Value("${app.orphan-retention-hours}") int orphanRetentionHours) {
        this.db=db;this.files=files;this.rules=rules;this.engine=engine;this.executable=executable;this.profile=profile;this.timeout=timeout;this.enabled=enabled;this.concurrency=Math.max(1,Math.min(concurrency,4));
        this.cleanupEnabled=cleanupEnabled;this.orphanRetentionHours=Math.max(1,orphanRetentionHours);
    }
    String fingerprint(Map<String,Object> version) { return hash(str(version,"schema_hash")+":"+(version.get("docx_file_id")==null?"":str(db.one("SELECT sha256 FROM files WHERE id=?",version.get("docx_file_id")),"sha256"))+":"+num(version,"lock_version")+":"+profile); }
    /**
     * 当前生效的渲染配置标识（app.render-profile）。修订与确认凭据都要记这个值，
     * 不能写死字面量，否则换了渲染环境（LibreOffice/字体）后审计里记录的就是错的。
     */
    String renderProfile() { return profile; }
    /** 转换服务可用性，供系统状态页显示（不暴露路径）。 */
    boolean converterAvailable() {
        try {
            if(executable==null||executable.isBlank()) return false;
            if(executable.contains("/")||executable.contains("\\")) return Files.isExecutable(Path.of(executable));
            String path=System.getenv("PATH");
            if(path==null) return false;
            for(String dir:path.split(java.io.File.pathSeparator)) {
                if(dir.isBlank()) continue;
                if(Files.isExecutable(Path.of(dir,executable))||Files.isRegularFile(Path.of(dir,executable+".exe"))) return true;
            }
        } catch(Exception ignored) { }
        return false;
    }
    Map<String,Object> enqueue(Map<String,Object> version,Map<String,Object> values,Map<String,Object> actor,String revisionId,String dedupe) {
        var existing=db.find("SELECT * FROM generation_jobs WHERE dedupe_key=?",dedupe);
        if(existing!=null) {
            if(str(existing,"state").equals("FAILED")) db.update("UPDATE generation_jobs SET state='QUEUED',attempt=0,error_code=NULL,actor_epoch=?,created_by=? WHERE id=?",actor.get("session_epoch"),actor.get("id"),existing.get("id"));
            return publicJob(db.one("SELECT * FROM generation_jobs WHERE id=?",existing.get("id")));
        }
        require(db.count("SELECT COUNT(*) FROM generation_jobs WHERE state IN ('QUEUED','RUNNING')")<100,429,"QUEUE_FULL","生成队列繁忙，请稍后重试");
        String id=id(); db.update("INSERT INTO generation_jobs(id,revision_id,template_version_id,created_by,actor_epoch,data_snapshot,fingerprint,dedupe_key) VALUES (?,?,?,?,?,?,?,?)",id,revisionId,version.get("id"),actor.get("id"),actor.get("session_epoch"),db.stringify(values),fingerprint(version),dedupe);
        return publicJob(db.one("SELECT * FROM generation_jobs WHERE id=?",id));
    }
    Map<String,Object> publicJob(Map<String,Object> job) {
        var result=new LinkedHashMap<String,Object>(); for(String key:List.of("id","revision_id","template_version_id","state","attempt","error_code","created_at","finished_at")) result.put(key,job.get(key)); return result;
    }
    @Scheduled(fixedDelay=1000) void tick() {
        if(!enabled) return;
        while(active.size()<concurrency) {
            var job=db.locked(()->{
                db.update("UPDATE generation_jobs SET state=CASE WHEN attempt<3 THEN 'QUEUED' ELSE 'FAILED' END,error_code='LEASE_EXPIRED',lease_token=NULL WHERE state='RUNNING' AND lease_until<?",at(Instant.now()));
                // 跳过本进程正在执行的任务，避免租约回收后同一任务被并发执行两次。
                var candidates=db.list("SELECT * FROM generation_jobs WHERE state='QUEUED' ORDER BY created_at LIMIT 20");
                var j=candidates.stream().filter(c->!active.containsKey(str(c,"id"))).findFirst().orElse(null);
                if(j==null) return null;
                String lease=id(); db.update("UPDATE generation_jobs SET state='RUNNING',attempt=attempt+1,lease_token=?,lease_until=?,heartbeat_at=? WHERE id=?",lease,at(Instant.now().plusSeconds(timeout+60)),at(Instant.now()),j.get("id"));
                return db.one("SELECT * FROM generation_jobs WHERE id=?",j.get("id"));
            });
            if(job==null) break;
            String jobId=str(job,"id"), lease=str(job,"lease_token");
            active.put(jobId,lease);
            executor.submit(()->{ try { run(job); } finally { active.remove(jobId,lease); } });
        }
    }
    /** 每小时回收孤儿文件，并清理过期的登录失败计数。 */
    @Scheduled(fixedDelay=3600000,initialDelay=60000) void housekeeping() {
        if(!enabled||!cleanupEnabled) return;
        try { files.purgeOrphans(orphanRetentionHours); } catch(Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("orphan cleanup failed: {}",e.getClass().getSimpleName()); }
        try { db.update("DELETE FROM login_attempts WHERE window_start<?",at(Instant.now().minusSeconds(86400))); } catch(Exception ignored) { }
    }
    Map<String,Object> authorizedVersion(Map<String,Object> job) {
        var actor=db.one("SELECT * FROM users WHERE id=?",job.get("created_by"));
        require(Boolean.TRUE.equals(actor.get("enabled"))&&num(actor,"session_epoch")==num(job,"actor_epoch"),403,"JOB_ACTOR_REVOKED","任务发起人权限已变更");
        var version=db.one("SELECT * FROM template_versions WHERE id=? AND deleted_at IS NULL",job.get("template_version_id"));
        db.one("SELECT id FROM templates WHERE id=? AND deleted_at IS NULL",version.get("template_id"));
        if(job.get("revision_id")==null) require(Set.of("ADMIN","CONTRACT_MAINTAINER").contains(str(actor,"role")),403,"JOB_ACTOR_REVOKED","模板维护权限已撤销");
        else {
            var contract=db.one("SELECT c.* FROM contracts c JOIN contract_revisions r ON r.contract_id=c.id WHERE r.id=? AND c.deleted_at IS NULL",job.get("revision_id"));
            require(str(actor,"role").equals("ADMIN")||Objects.equals(contract.get("owner_id"),actor.get("id")),403,"JOB_ACTOR_REVOKED","合同权限已变更");
        }
        require(fingerprint(version).equals(job.get("fingerprint")),409,"TEMPLATE_CHANGED","模板草稿已改变，请重新试生成"); return version;
    }
    void run(Map<String,Object> job) {
        Path work=null;
        try {
            var version=db.locked(()->authorizedVersion(job));
            work=Files.createTempDirectory(files.root,"convert-");
            byte[] docx=engine.render(files.read(str(version,"docx_file_id")),obj(version,"field_schema"),rules.renderValues(obj(version,"field_schema"),obj(job,"data_snapshot")));
            Files.write(work.resolve("contract.docx"),docx);
            Process process;
            try { process=new ProcessBuilder(executable,"-env:UserInstallation="+work.resolve("profile").toUri(),"--headless","--nologo","--nodefault","--norestore","--convert-to","pdf:writer_pdf_Export","--outdir",work.toString(),work.resolve("contract.docx").toString()).redirectErrorStream(true).redirectOutput(work.resolve("conversion.log").toFile()).start(); }
            catch(java.io.IOException e) { throw new ApiException(503,"CONVERTER_UNAVAILABLE","LibreOffice 转换服务未安装或未配置"); }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout);
            try {
                while(!process.waitFor(5,TimeUnit.SECONDS)) {
                    if(System.nanoTime()>=deadline) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); throw new ApiException(503,"CONVERSION_TIMEOUT","文档转换超时"); }
                    db.update("UPDATE generation_jobs SET heartbeat_at=?,lease_until=? WHERE id=? AND lease_token=? AND state='RUNNING'",at(Instant.now()),at(Instant.now().plusSeconds(timeout+60)),job.get("id"),job.get("lease_token"));
                }
            } catch(InterruptedException e) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); Thread.currentThread().interrupt(); throw e; }
            require(process.exitValue()==0&&Files.isRegularFile(work.resolve("contract.pdf")),503,"CONVERSION_FAILED","文档转换失败");
            byte[] pdf=Files.readAllBytes(work.resolve("contract.pdf")); files.checkPdf(pdf,300);
            // 先落盘（不在全局锁内做磁盘 IO），再在锁内校验租约并登记引用；
            // 若期间租约失效，留下的无引用文件由每小时的对账任务回收。
            var word=files.store(docx,"contract.docx","docx","GENERATED",str(job,"created_by"));
            var portable=files.store(pdf,"contract.pdf","pdf","GENERATED",str(job,"created_by"));
            db.locked(()->{
                authorizedVersion(job);
                var current=db.one("SELECT * FROM generation_jobs WHERE id=?",job.get("id"));
                require(str(current,"state").equals("RUNNING")&&Objects.equals(current.get("lease_token"),job.get("lease_token"))&&Instant.parse(str(current,"lease_until")).isAfter(Instant.now()),409,"LEASE_LOST","任务租约已失效");
                db.update("UPDATE generation_jobs SET state='SUCCEEDED',docx_file_id=?,pdf_file_id=?,finished_at=?,error_code=NULL WHERE id=? AND lease_token=?",word.get("id"),portable.get("id"),at(Instant.now()),job.get("id"),job.get("lease_token"));
                db.audit(str(job,"created_by"),"GENERATION_SUCCEEDED","JOB",str(job,"id"),Map.of("docx_sha256",word.get("sha256"),"pdf_sha256",portable.get("sha256"))); return null;
            });
        } catch(Exception e) {
            String code=e instanceof ApiException a?a.code:"GENERATION_FAILED";
            db.locked(()->{
                boolean retry=Set.of("CONVERSION_FAILED","CONVERSION_TIMEOUT").contains(code)&&num(job,"attempt")<3;
                db.update("UPDATE generation_jobs SET state=?,error_code=?,finished_at=? WHERE id=? AND lease_token=? AND state='RUNNING'",retry?"QUEUED":"FAILED",code,at(Instant.now()),job.get("id"),job.get("lease_token"));
                db.audit(str(job,"created_by"),"GENERATION_FAILED","JOB",str(job,"id"),Map.of("error_code",code,"error_message",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())); return null;
            });
        } finally {
            if(work!=null) try(var paths=Files.walk(work)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } catch(Exception ignored) { }
        }
    }
    @PreDestroy void close() { executor.shutdownNow(); }
}
