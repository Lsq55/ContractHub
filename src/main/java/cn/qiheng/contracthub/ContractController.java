package cn.qiheng.contracthub;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static cn.qiheng.contracthub.Db.*;
import static cn.qiheng.contracthub.ApiException.require;

@RestController
@RequestMapping("/api/v1")
public class ContractController {
    final Db db; final AuthService auth; final FieldRules rules; final GenerationService generation; final FileStore files; final TemplateController templates;
    ContractController(Db db,AuthService auth,FieldRules rules,GenerationService generation,FileStore files,TemplateController templates) { this.db=db;this.auth=auth;this.rules=rules;this.generation=generation;this.files=files;this.templates=templates; }
    boolean admin(HttpServletRequest r) { return str(auth.actor(r),"role").equals("ADMIN"); }
    Map<String,Object> contract(String id,HttpServletRequest r) {
        var c=db.one("SELECT c.*,t.name AS template_name,t.code AS template_code,v.version_no FROM contracts c JOIN templates t ON t.id=c.template_id JOIN template_versions v ON v.id=c.template_version_id WHERE c.id=? AND c.deleted_at IS NULL",id);
        require(admin(r)||Objects.equals(c.get("owner_id"),auth.actor(r).get("id")),404,"NOT_FOUND","合同不存在或无权访问"); return c;
    }
    Map<String,Object> revision(String id) { return db.one("SELECT r.*,c.owner_id,c.status,c.template_id,c.template_version_id AS current_template_version_id FROM contract_revisions r JOIN contracts c ON c.id=r.contract_id WHERE r.id=?",id); }
    Map<String,Object> template(String id) { return db.one("SELECT * FROM templates WHERE id=? AND deleted_at IS NULL",id); }
    /** 按目标版本的迁移映射与字段配置整理旧数据：映射改名、丢弃目标版本不存在的字段，再按新规则校验。 */
    Map<String,Object> migrateData(Map<String,Object> oldData,Map<String,Object> target) {
        var migration=obj(target,"migration_map"); var schema=obj(target,"field_schema");
        var keys=new HashSet<String>(); fields(schema).forEach(f->keys.add(str(f,"key")));
        var mapped=new LinkedHashMap<String,Object>();
        oldData.forEach((k,v)->{ String to=str(migration,k).isBlank()?k:str(migration,k); if(keys.contains(to)) mapped.put(to,v); });
        try { return rules.validate(schema,mapped,false); } catch(ApiException e) { return mapped; }
    }
    @GetMapping("/contracts") Object list(HttpServletRequest r,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        size=Math.max(1,Math.min(size,100)); page=Math.max(page,1); boolean all=admin(r); String owner=all?"%":str(auth.actor(r),"id"); String term="%"+q.toLowerCase(Locale.ROOT)+"%";
        return Map.of("items",db.list("SELECT c.*,t.name AS template_name,v.version_no FROM contracts c JOIN templates t ON t.id=c.template_id JOIN template_versions v ON v.id=c.template_version_id WHERE c.deleted_at IS NULL AND (?='%' OR c.owner_id=?) AND (LOWER(c.title) LIKE ? OR LOWER(c.contract_no) LIKE ?) AND (?='' OR c.status=?) ORDER BY c.updated_at DESC LIMIT ? OFFSET ?",owner,auth.actor(r).get("id"),term,term,status,status,size,(page-1)*size),"total",db.count("SELECT COUNT(*) FROM contracts c WHERE c.deleted_at IS NULL AND (?='%' OR c.owner_id=?) AND (LOWER(c.title) LIKE ? OR LOWER(c.contract_no) LIKE ?) AND (?='' OR c.status=?)",owner,auth.actor(r).get("id"),term,term,status,status),"page",page,"size",size);
    }
    /** 工作台统计：由服务端聚合，避免前端只统计当前一页导致数字错误。 */
    @GetMapping("/contracts/stats") Object stats(HttpServletRequest r) {
        boolean all=admin(r); String owner=all?"%":str(auth.actor(r),"id");
        var rows=db.list("SELECT status,COUNT(*) AS amount FROM contracts WHERE deleted_at IS NULL AND (?='%' OR owner_id=?) GROUP BY status",owner,str(auth.actor(r),"id"));
        var out=new LinkedHashMap<String,Object>(); long total=0;
        for(String status:List.of("DRAFT","FINALIZED","SIGNED","VOID")) out.put(status.toLowerCase(Locale.ROOT),0L);
        for(var row:rows) { long amount=((Number)row.get("amount")).longValue(); total+=amount; out.put(str(row,"status").toLowerCase(Locale.ROOT),amount); }
        out.put("total",total); return out;
    }
    /**
     * 合同编号：HT-年份-6 位序号。
     * 序号取"今年已用编号里的最小空位"，而不是用数据库序列（序列删了合同也不会回退，测试时会一直往上跳）：
     * 删光了就从 000001 重新开始，删掉中间某个就补那个空位，编号始终是连续的 001…N。
     * 调用点都在 auth.write 的全局锁里，不存在并发抢同一个号的问题；contract_no 上还有唯一约束兜底。
     */
    String nextContractNo() {
        int year=Year.now().getValue(); String prefix="HT-"+year+"-";
        var existing=new java.util.ArrayList<String>();
        for(var row:db.list("SELECT contract_no FROM contracts WHERE contract_no LIKE ?",prefix+"%")) existing.add(str(row,"contract_no"));
        return contractNoFrom(existing,year);
    }
    /** 纯函数：给定今年已有的合同编号，算出下一个可用编号（方便单测与复用）。 */
    static String contractNoFrom(java.util.Collection<String> existing,int year) {
        String prefix="HT-"+year+"-";
        var used=new java.util.HashSet<Integer>();
        for(String value:existing) {
            if(value==null||!value.startsWith(prefix)) continue;
            try { used.add(Integer.valueOf(value.substring(prefix.length()))); } catch(NumberFormatException ignored) { }
        }
        int no=1; while(used.contains(no)) no++;
        return prefix+String.format("%06d",no);
    }
    @PostMapping("/contracts") Object create(@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        String vid=required(b,"template_version_id",36); var v=db.one("SELECT * FROM template_versions WHERE id=? AND deleted_at IS NULL AND state='PUBLISHED'",vid); var t=db.one("SELECT * FROM templates WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL",v.get("template_id"));
        // 新建必须使用当前发布版本，防止绕过"最新版本"规则基于旧版本起草。
        require(Objects.equals(t.get("current_version_id"),vid),409,"TEMPLATE_OUTDATED","请使用模板当前发布版本新建合同");
        String cid=id(),no=nextContractNo(); String title=required(b,"title",150);
        var schema=obj(v,"field_schema"); rules.schema(schema);
        var seed=defaults(schema);   // 模板里配了默认值的字段，建草稿时就带上，省得每次重填
        db.update("INSERT INTO contracts(id,contract_no,title,owner_id,template_id,template_version_id,form_data) VALUES (?,?,?,?,?,?,?)",cid,no,title,auth.actor(r).get("id"),t.get("id"),vid,db.stringify(seed)); db.audit(str(auth.actor(r),"id"),"CONTRACT_CREATED","CONTRACT",cid,Map.of("contract_no",no,"template_version",v.get("version_no"))); return contract(cid,r);
    }); }
    /** 字段配置里写了 default 的，作为新建合同的初始填报值（计算字段由后端重算，不参与）。 */
    static Map<String,Object> defaults(Map<String,Object> schema) {
        var seed=new LinkedHashMap<String,Object>();
        for(var f:fields(schema)) { if(str(f,"type").equals("computed")) continue; Object d=f.get("default"); if(d!=null) seed.put(str(f,"key"),d); }
        return seed;
    }
    @GetMapping("/contracts/{id}") Object detail(@PathVariable String id,HttpServletRequest r) {
        var c=contract(id,r);
        var out=new LinkedHashMap<String,Object>(c);
        var t=db.find("SELECT * FROM templates WHERE id=?",c.get("template_id"));
        if(t!=null) {
            out.put("template_status",t.get("status"));
            out.put("latest_version_id",t.get("current_version_id"));
            var latest=t.get("current_version_id")==null?null:db.find("SELECT version_no FROM template_versions WHERE id=?",t.get("current_version_id"));
            out.put("latest_version_no",latest==null?null:latest.get("version_no"));
        }
        var rev=c.get("current_revision_id")==null?null:db.find("SELECT * FROM contract_revisions WHERE id=?",c.get("current_revision_id"));
        if(rev!=null) {
            out.put("current_revision_no",rev.get("revision_no"));
            out.put("current_revision_matches_draft",num(rev,"draft_lock_version")==num(c,"lock_version")&&Objects.equals(str(rev,"template_version_id"),str(c,"template_version_id")));
        }
        var job=rev==null?null:db.find("SELECT * FROM generation_jobs WHERE revision_id=? ORDER BY created_at DESC LIMIT 1",rev.get("id"));
        if(job!=null) { out.put("job_id",job.get("id")); out.put("job_state",job.get("state")); out.put("job_error",job.get("error_code")); }
        if(!str(c,"finalized_revision_id").isBlank()) {
            var frozen=db.find("SELECT revision_no FROM contract_revisions WHERE id=?",c.get("finalized_revision_id"));
            if(frozen!=null) out.put("finalized_revision_no",frozen.get("revision_no"));
        }
        out.put("signed_attachments",db.list("SELECT a.id,a.signed_on,a.note,a.supersedes_id,a.created_at,a.voided_at,a.void_reason,f.original_name,f.byte_size,f.sha256 FROM signed_attachments a JOIN files f ON f.id=a.file_id WHERE a.contract_id=? ORDER BY a.created_at DESC",id));
        return out;
    }
    @PatchMapping("/contracts/{id}") Object save(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var c=contract(id,r); require(str(c,"status").equals("DRAFT"),409,"CONTRACT_FROZEN","定稿合同不可直接修改，请派生新草稿"); lockVersion(c,b);
        var v=db.one("SELECT * FROM template_versions WHERE id=?",c.get("template_version_id")); var form=rules.validate(obj(v,"field_schema"),obj(b,"form_data"),false);
        String title=b.containsKey("title")?required(b,"title",150):str(c,"title"); db.update("UPDATE contracts SET title=?,form_data=?,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",title,db.stringify(form),id); db.audit(str(auth.actor(r),"id"),"CONTRACT_SAVED","CONTRACT",id,Map.of("lock_version",num(c,"lock_version")+1)); return contract(id,r);
    }); }
    @PostMapping("/contracts/{id}/upgrade-preview") Object upgradePreview(@PathVariable String id,HttpServletRequest r) { return auth.write(r,()->{
        var c=contract(id,r); require(str(c,"status").equals("DRAFT"),409,"CONTRACT_FROZEN","定稿合同不可升级"); var t=template(str(c,"template_id")); require(t.get("current_version_id")!=null,409,"TEMPLATE_UNPUBLISHED","模板尚未发布"); var target=db.one("SELECT * FROM template_versions WHERE id=? AND state='PUBLISHED' AND deleted_at IS NULL",t.get("current_version_id"));
        if(Objects.equals(target.get("id"),c.get("template_version_id"))) return Map.of("up_to_date",true,"version_no",target.get("version_no"));
        var old=db.one("SELECT * FROM template_versions WHERE id=?",c.get("template_version_id"));
        return Map.of("up_to_date",false,"from_version",old.get("version_no"),"to_version",target.get("version_no"),"target_template_version_id",target.get("id"),"data",migrateData(obj(c,"form_data"),target),"schema",target.get("field_schema"));
    }); }
    @PostMapping("/contracts/{id}/upgrade") Object upgrade(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var c=contract(id,r); require(str(c,"status").equals("DRAFT"),409,"CONTRACT_FROZEN","定稿合同不可升级"); lockVersion(c,b); String targetId=required(b,"target_template_version_id",36); var target=db.one("SELECT * FROM template_versions WHERE id=? AND state='PUBLISHED' AND deleted_at IS NULL",targetId); require(target.get("template_id").equals(c.get("template_id")),422,"INVALID_VERSION","目标版本不属于当前模板");
        var values=rules.validate(obj(target,"field_schema"),obj(b,"form_data"),false); db.update("UPDATE contracts SET template_version_id=?,form_data=?,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",targetId,db.stringify(values),id); db.audit(str(auth.actor(r),"id"),"CONTRACT_UPGRADED","CONTRACT",id,Map.of("template_version",target.get("version_no"))); return contract(id,r);
    }); }
    /** 同一草稿快照（草稿未改、模板版本未变）且已有可用任务时复用该修订，落实"重复点击不重复生成"。 */
    String reusableRevision(Map<String,Object> c) {
        if(c.get("current_revision_id")==null) return null;
        var rev=db.find("SELECT * FROM contract_revisions WHERE id=?",c.get("current_revision_id"));
        if(rev==null) return null;
        if(num(rev,"draft_lock_version")!=num(c,"lock_version")||!Objects.equals(str(rev,"template_version_id"),str(c,"template_version_id"))) return null;
        var job=db.find("SELECT * FROM generation_jobs WHERE revision_id=? ORDER BY created_at DESC LIMIT 1",rev.get("id"));
        if(job==null) return null;
        return str(job,"state").equals("FAILED")?null:str(rev,"id");
    }
    @PostMapping("/contracts/{id}/generate") @ResponseStatus(HttpStatus.ACCEPTED) Object generate(@PathVariable String id,HttpServletRequest r) { return auth.write(r,()->{
        var c=contract(id,r); require(str(c,"status").equals("DRAFT"),409,"CONTRACT_FROZEN","只有草稿可以生成"); var t=template(str(c,"template_id"));
        require(t.get("current_version_id")!=null,409,"TEMPLATE_UNPUBLISHED","模板尚未发布，无法生成");
        var v=db.one("SELECT * FROM template_versions WHERE id=? AND state='PUBLISHED'",t.get("current_version_id"));
        require(v.get("id").equals(c.get("template_version_id")),409,"TEMPLATE_OUTDATED","模板已更新，请先升级合同");
        var values=rules.validate(obj(v,"field_schema"),obj(c,"form_data"),true);
        String reusable=reusableRevision(c);
        if(reusable!=null) {
            var job=db.one("SELECT * FROM generation_jobs WHERE revision_id=? ORDER BY created_at DESC LIMIT 1",reusable);
            db.update("UPDATE contracts SET updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
            db.audit(str(auth.actor(r),"id"),"GENERATION_REUSED","CONTRACT",id,Map.of("revision_id",reusable,"job_id",job.get("id")));
            return Map.of("revision_id",reusable,"job",generation.publicJob(job),"reused",true);
        }
        int no=(int)db.count("SELECT COALESCE(MAX(revision_no),0)+1 FROM contract_revisions WHERE contract_id=?",id); String rid=id(); String fp=generation.fingerprint(v);
        db.update("INSERT INTO contract_revisions(id,contract_id,revision_no,template_version_id,data_snapshot,draft_lock_version,template_hash,schema_hash,render_profile_id,created_by) VALUES (?,?,?,?,?,?,?,?,?,?)",rid,id,no,v.get("id"),db.stringify(values),num(c,"lock_version"),str(db.one("SELECT sha256 FROM files WHERE id=?",v.get("docx_file_id")),"sha256"),v.get("schema_hash"),"libreoffice-noto-cjk-v1",auth.actor(r).get("id"));
        var job=generation.enqueue(v,values,auth.actor(r),rid,id+":"+rid+":"+fp);
        db.update("UPDATE contracts SET current_revision_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",rid,id); db.audit(str(auth.actor(r),"id"),"GENERATION_REQUESTED","CONTRACT",id,Map.of("revision_id",rid,"job_id",job.get("id"))); return Map.of("revision_id",rid,"job",job,"reused",false);
    }); }
    @GetMapping("/generation-jobs/{id}") Object job(@PathVariable String id,HttpServletRequest r) { var j=db.one("SELECT * FROM generation_jobs WHERE id=?",id); var rev=j.get("revision_id")==null?null:revision(str(j,"revision_id")); require(admin(r)||Objects.equals(j.get("created_by"),auth.actor(r).get("id"))||(rev!=null&&Objects.equals(rev.get("owner_id"),auth.actor(r).get("id"))),404,"NOT_FOUND","任务不存在或无权访问"); return generation.publicJob(j); }
    @GetMapping("/contracts/{id}/revisions/{rid}/preview") Object preview(@PathVariable String id,@PathVariable String rid,HttpServletRequest r) {
        var c=contract(id,r); var rev=revision(rid); require(id.equals(rev.get("contract_id")),404,"NOT_FOUND","修订不存在"); var j=db.one("SELECT * FROM generation_jobs WHERE revision_id=? AND state='SUCCEEDED'",rid);
        String sha=str(db.one("SELECT sha256 FROM files WHERE id=?",j.get("pdf_file_id")),"sha256");
        db.recordPreviewView(str(auth.actor(r),"id"),str(j,"id"),sha);
        return templates.download(str(j,"pdf_file_id"),str(c,"contract_no")+"-DRAFT-V"+rev.get("revision_no")+".pdf",true,r,"PREVIEWED",""+rid);
    }
    @PostMapping("/contracts/{id}/revisions/{rid}/review") Object review(@PathVariable String id,@PathVariable String rid,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        contract(id,r); var rev=revision(rid); require(id.equals(rev.get("contract_id")),404,"NOT_FOUND","修订不存在"); require(Boolean.TRUE.equals(b.get("confirmed")),422,"CONFIRMATION_REQUIRED","请确认已查看完整 PDF"); var j=db.one("SELECT * FROM generation_jobs WHERE revision_id=? AND state='SUCCEEDED'",rid); var pdf=db.one("SELECT * FROM files WHERE id=?",j.get("pdf_file_id")); require(db.count("SELECT COUNT(*) FROM preview_views WHERE user_id=? AND job_id=?",auth.actor(r).get("id"),j.get("id"))>0,422,"PREVIEW_REQUIRED","请先打开本次完整 PDF");
        String token=id(); Instant expires=Instant.now().plusSeconds(auth.reviewTtlMinutes*60L);
        // 凭据同时记录母版/字段/渲染配置指纹，定稿时可核对"确认的就是这一版全文"。
        db.update("INSERT INTO review_receipts(id,user_id,revision_id,pdf_sha256,template_hash,schema_hash,render_profile_id,expires_at) VALUES (?,?,?,?,?,?,?,?)",token,auth.actor(r).get("id"),rid,pdf.get("sha256"),rev.get("template_hash"),rev.get("schema_hash"),rev.get("render_profile_id"),at(expires));
        db.audit(str(auth.actor(r),"id"),"PREVIEW_REVIEWED","REVISION",rid,Map.of("pdf_sha256",pdf.get("sha256"))); return Map.of("review_receipt_id",token,"expires_at",at(expires));
    }); }
    @PostMapping("/contracts/{id}/finalize") Object finalize(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var c=contract(id,r); require(str(c,"status").equals("DRAFT"),409,"CONTRACT_STATE_CONFLICT","合同状态不允许定稿"); lockVersion(c,b); String rid=required(b,"revision_id",36),receipt=required(b,"review_receipt_id",36); var rev=revision(rid); require(id.equals(rev.get("contract_id")),409,"REVISION_MISMATCH","修订不属于当前合同");
        var j=db.one("SELECT * FROM generation_jobs WHERE revision_id=? AND state='SUCCEEDED'",rid); var rec=db.one("SELECT * FROM review_receipts WHERE id=? AND user_id=? AND revision_id=? AND expires_at>?",receipt,auth.actor(r).get("id"),rid,at(Instant.now())); var pdf=db.one("SELECT * FROM files WHERE id=?",j.get("pdf_file_id"));
        require(pdf.get("sha256").equals(rec.get("pdf_sha256"))&&Objects.equals(str(rec,"template_hash"),str(rev,"template_hash"))&&Objects.equals(str(rec,"schema_hash"),str(rev,"schema_hash"))&&db.count("SELECT COUNT(*) FROM preview_views WHERE user_id=? AND job_id=?",auth.actor(r).get("id"),j.get("id"))>0,409,"REVIEW_OUTDATED","确认凭据与当前 PDF 不一致，请重新预览确认");
        var t=template(str(c,"template_id")); require(Objects.equals(t.get("current_version_id"),c.get("template_version_id")),409,"TEMPLATE_OUTDATED","模板已更新，请先升级合同"); require(num(rev,"draft_lock_version")==num(c,"lock_version"),409,"EDIT_CONFLICT","草稿在生成后发生了修改，请重新生成");
        db.update("UPDATE contracts SET status='FINALIZED',finalized_revision_id=?,finalized_at=?,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='DRAFT' AND lock_version=?",rid,at(Instant.now()),id,c.get("lock_version")); require(db.count("SELECT COUNT(*) FROM contracts WHERE id=? AND status='FINALIZED'",id)>0,409,"CONTRACT_STATE_CONFLICT","合同已经被其他请求定稿"); db.audit(str(auth.actor(r),"id"),"CONTRACT_FINALIZED","CONTRACT",id,Map.of("revision_id",rid,"pdf_sha256",pdf.get("sha256"))); return contract(id,r);
    }); }
    @GetMapping("/contracts/{id}/download") Object download(@PathVariable String id,@RequestParam(defaultValue="pdf") String format,HttpServletRequest r) { var c=contract(id,r); require(Set.of("docx","pdf").contains(format),422,"INVALID_FORMAT","文件格式无效"); String rid=str(c,"finalized_revision_id"); require(!rid.isBlank(),409,"CONTRACT_NOT_FINALIZED","合同尚未定稿"); var j=db.one("SELECT * FROM generation_jobs WHERE revision_id=? AND state='SUCCEEDED'",rid); return templates.download(str(j,format.equals("pdf")?"pdf_file_id":"docx_file_id"),str(c,"contract_no")+"-FINAL-V"+revision(rid).get("revision_no")+"."+format,false,r,"CONTRACT_DOWNLOADED",id); }
    /** 历史入口只放行被定稿冻结的那个修订，未定稿的中间草稿不可下载。 */
    @GetMapping("/contracts/{id}/revisions/{rid}/download") Object history(@PathVariable String id,@PathVariable String rid,@RequestParam(defaultValue="pdf") String format,HttpServletRequest r) { var c=contract(id,r); require(Set.of("docx","pdf").contains(format),422,"INVALID_FORMAT","文件格式无效"); var rev=revision(rid); require(id.equals(rev.get("contract_id"))&&rid.equals(str(c,"finalized_revision_id")),404,"NOT_FOUND","历史定稿不存在"); var j=db.one("SELECT * FROM generation_jobs WHERE revision_id=? AND state='SUCCEEDED'",rid); return templates.download(str(j,format.equals("pdf")?"pdf_file_id":"docx_file_id"),str(c,"contract_no")+"-FINAL-R"+rev.get("revision_no")+"."+format,false,r,"HISTORY_DOWNLOADED",rid); }
    @GetMapping("/contracts/{id}/signed-attachments") Object signedList(@PathVariable String id,HttpServletRequest r) { contract(id,r); return db.list("SELECT a.id,a.signed_on,a.note,a.supersedes_id,a.created_at,a.voided_at,a.void_reason,f.original_name,f.byte_size,f.sha256 FROM signed_attachments a JOIN files f ON f.id=a.file_id WHERE a.contract_id=? ORDER BY a.created_at DESC",id); }
    @PostMapping("/contracts/{id}/signed-attachments") Object signed(@PathVariable String id,@RequestParam MultipartFile file,@RequestParam String signed_on,@RequestParam(defaultValue="") String note,HttpServletRequest r) {
        // 文件校验与落盘放在全局锁之外，锁内只做归属校验与数据库写入。
        var pre=contract(id,r); require(Set.of("FINALIZED","SIGNED").contains(str(pre,"status")),409,"CONTRACT_NOT_FINALIZED","只有定稿或已签署合同可归档签署件，已作废合同不可再归档");
        LocalDate signedOn; try { signedOn=LocalDate.parse(signed_on); } catch(java.time.format.DateTimeParseException e) { throw new ApiException(422,"INVALID_DATE","签署日期格式应为 YYYY-MM-DD"); }
        require(signedOn.isAfter(LocalDate.of(1900,1,1))&&!signedOn.isAfter(LocalDate.now().plusDays(1)),422,"INVALID_DATE","签署日期不合理");
        require(note.trim().length()<=1000,422,"INVALID_INPUT","备注不能超过 1000 字");
        var f=files.upload(file,"SIGNED_ATTACHMENT",str(auth.actor(r),"id"),"pdf");
        return auth.write(r,()->{
            var c=contract(id,r); require(Set.of("FINALIZED","SIGNED").contains(str(c,"status")),409,"CONTRACT_NOT_FINALIZED","合同状态已变化，无法归档签署件");
            String aid=id(); var prev=db.find("SELECT id FROM signed_attachments WHERE contract_id=? AND voided_at IS NULL ORDER BY created_at DESC LIMIT 1",id);
            db.update("INSERT INTO signed_attachments(id,contract_id,finalized_revision_id,file_id,signed_on,note,supersedes_id,created_by) VALUES (?,?,?,?,?,?,?,?)",aid,id,c.get("finalized_revision_id"),f.get("id"),signedOn,note,prev==null?null:prev.get("id"),auth.actor(r).get("id"));
            db.update("UPDATE contracts SET status='SIGNED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='FINALIZED'",id); db.audit(str(auth.actor(r),"id"),"SIGNED_ATTACHMENT_ADDED","CONTRACT",id,Map.of("signed_on",signed_on,"sha256",f.get("sha256"))); return Map.of("id",aid,"file",Map.of("id",f.get("id"),"sha256",f.get("sha256")),"signed_on",signed_on);
        });
    }
    @GetMapping("/contracts/{id}/signed-attachments/{aid}/download") Object signedDownload(@PathVariable String id,@PathVariable String aid,HttpServletRequest r) { contract(id,r); var a=db.one("SELECT a.*,f.original_name FROM signed_attachments a JOIN files f ON f.id=a.file_id WHERE a.id=? AND a.contract_id=?",aid,id); return templates.download(str(a,"file_id"),"签署件-"+str(a,"signed_on")+"-"+str(a,"original_name"),false,r,"SIGNED_ATTACHMENT_DOWNLOADED",id); }
    @PostMapping("/contracts/{id}/signed-attachments/{aid}/void") Object signedVoid(@PathVariable String id,@PathVariable String aid,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        contract(id,r); String reason=required(b,"reason",1000); var a=db.one("SELECT * FROM signed_attachments WHERE id=? AND contract_id=?",aid,id); require(a.get("voided_at")==null,409,"ALREADY_VOID","该签署件已作废");
        db.update("UPDATE signed_attachments SET voided_at=?,voided_by=?,void_reason=? WHERE id=?",at(Instant.now()),auth.actor(r).get("id"),reason,aid); db.audit(str(auth.actor(r),"id"),"SIGNED_ATTACHMENT_VOIDED","CONTRACT",id,Map.of("attachment_id",aid,"reason",reason)); return Map.of("ok",true);
    }); }
    @PostMapping("/contracts/{id}/void") Object voidContract(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{ var c=contract(id,r); require(!str(c,"status").equals("DRAFT")&&!str(c,"status").equals("VOID"),409,"CONTRACT_STATE_CONFLICT","当前状态不能作废"); String reason=required(b,"reason",1000); db.update("UPDATE contracts SET status='VOID',void_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",reason,id); db.audit(str(auth.actor(r),"id"),"CONTRACT_VOIDED","CONTRACT",id,Map.of("reason",reason)); return contract(id,r); }); }
    @PostMapping("/contracts/{id}/derive") Object derive(@PathVariable String id,HttpServletRequest r) { return auth.write(r,()->{
        var c=contract(id,r); require(!str(c,"status").equals("DRAFT"),409,"CONTRACT_STATE_CONFLICT","草稿无需派生"); var t=template(str(c,"template_id")); var v=db.one("SELECT * FROM template_versions WHERE id=? AND state='PUBLISHED'",t.get("current_version_id"));
        String cid=id(),no=nextContractNo();
        // 旧数据必须按当前模板版本的字段配置迁移并校验，否则派生出的草稿会带未知字段而无法保存。
        var migrated=migrateData(obj(c,"form_data"),v);
        db.update("INSERT INTO contracts(id,contract_no,title,owner_id,template_id,template_version_id,form_data,parent_contract_id) VALUES (?,?,?,?,?,?,?,?)",cid,no,str(c,"title")+"（派生）",c.get("owner_id"),t.get("id"),v.get("id"),db.stringify(migrated),id);
        db.audit(str(auth.actor(r),"id"),"CONTRACT_DERIVED","CONTRACT",cid,Map.of("contract_no",no,"parent_contract_id",id)); return contract(cid,r);
    }); }
    /** 文档 3.2：账号停用后管理员可以重新分配合同所有者。 */
    @PostMapping("/contracts/{id}/owner") Object reassign(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var c=contract(id,r); String owner=required(b,"owner_id",36); String reason=required(b,"reason",1000);
        var target=db.one("SELECT * FROM users WHERE id=? AND deleted_at IS NULL",owner); require(Boolean.TRUE.equals(target.get("enabled")),422,"INVALID_OWNER","目标账号未启用");
        db.update("UPDATE contracts SET owner_id=?,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",owner,id);
        db.audit(str(auth.actor(r),"id"),"CONTRACT_OWNER_CHANGED","CONTRACT",id,Map.of("from",c.get("owner_id"),"to",owner,"reason",reason)); return contract(id,r);
    },"ADMIN"); }
    /**
     * 彻底删除合同（物理删除，不保留）：删除合同、全部修订、生成任务、预览/确认凭据、签署件，
     * 并回收只被它引用的 Word/PDF 文件；审计记录保留（追加式，记录"删了什么、谁删的、为什么"）。
     * 权限：草稿——有权限的用户可删；已作废——仅管理员；已定稿/已签署必须先作废。
     */
    @DeleteMapping("/contracts/{id}") Object delete(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var c=contract(id,r); String status=str(c,"status");
        require(status.equals("DRAFT")||status.equals("VOID"),409,"CONTRACT_FROZEN","仅草稿和已作废合同可以彻底删除；已定稿或已签署请先作废");
        if(status.equals("VOID")) require(str(auth.actor(r),"role").equals("ADMIN"),403,"FORBIDDEN","彻底删除已作废合同需要管理员权限");
        lockVersion(c,b); String reason=required(b,"reason",1000);
        var fileRows=db.list("SELECT DISTINCT f.id FROM files f WHERE f.id IN (SELECT docx_file_id FROM generation_jobs WHERE revision_id IN (SELECT id FROM contract_revisions WHERE contract_id=?)) OR f.id IN (SELECT pdf_file_id FROM generation_jobs WHERE revision_id IN (SELECT id FROM contract_revisions WHERE contract_id=?)) OR f.id IN (SELECT file_id FROM signed_attachments WHERE contract_id=?)",id,id,id);
        long revisions=db.count("SELECT COUNT(*) FROM contract_revisions WHERE contract_id=?",id);
        long jobs=db.count("SELECT COUNT(*) FROM generation_jobs WHERE revision_id IN (SELECT id FROM contract_revisions WHERE contract_id=?)",id);
        long attachments=db.count("SELECT COUNT(*) FROM signed_attachments WHERE contract_id=?",id);
        // 外键顺序：先断开自引用与当前/定稿指针，再自下而上删除
        db.update("UPDATE contracts SET current_revision_id=NULL,finalized_revision_id=NULL WHERE id=?",id);
        db.update("UPDATE contracts SET parent_contract_id=NULL WHERE parent_contract_id=?",id);
        db.update("UPDATE signed_attachments SET supersedes_id=NULL WHERE contract_id=?",id);
        db.update("DELETE FROM preview_views WHERE job_id IN (SELECT id FROM generation_jobs WHERE revision_id IN (SELECT id FROM contract_revisions WHERE contract_id=?))",id);
        db.update("DELETE FROM review_receipts WHERE revision_id IN (SELECT id FROM contract_revisions WHERE contract_id=?)",id);
        db.update("DELETE FROM signed_attachments WHERE contract_id=?",id);
        db.update("DELETE FROM generation_jobs WHERE revision_id IN (SELECT id FROM contract_revisions WHERE contract_id=?)",id);
        db.update("DELETE FROM contract_revisions WHERE contract_id=?",id);
        db.update("DELETE FROM contracts WHERE id=?",id);
        long filesPurged=0; for(var f:fileRows) if(files.purgeIfUnreferenced(str(f,"id"))) filesPurged++;
        db.audit(str(auth.actor(r),"id"),"CONTRACT_PURGED","CONTRACT",id,Map.of("contract_no",str(c,"contract_no"),"reason",reason,"previous_status",status,"revisions",revisions,"jobs",jobs,"attachments",attachments,"files",filesPurged));
        return Map.of("ok",true,"revisions",revisions,"jobs",jobs,"attachments",attachments,"files",filesPurged);
    }); }
}
