package cn.qiheng.contracthub;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static cn.qiheng.contracthub.Db.*;
import static cn.qiheng.contracthub.ApiException.require;

@RestController
@RequestMapping("/api/v1")
public class TemplateController {
    final Db db; final AuthService auth; final FileStore files; final FieldRules rules; final DocumentEngine engine; final GenerationService generation;
    TemplateController(Db db,AuthService auth,FileStore files,FieldRules rules,DocumentEngine engine,GenerationService generation) { this.db=db;this.auth=auth;this.files=files;this.rules=rules;this.engine=engine;this.generation=generation; }
    void maintain(HttpServletRequest r) { auth.role(auth.actor(r),"ADMIN","CONTRACT_MAINTAINER"); }
    Map<String,Object> template(String id) { return db.one("SELECT t.*,u.display_name AS maintainer_name,v.version_no AS current_version_no FROM templates t JOIN users u ON u.id=t.created_by LEFT JOIN template_versions v ON v.id=t.current_version_id WHERE t.id=? AND t.deleted_at IS NULL",id); }
    Map<String,Object> version(String id) { var v=db.one("SELECT * FROM template_versions WHERE id=? AND deleted_at IS NULL",id); template(str(v,"template_id")); return v; }
    void draft(Map<String,Object> v) { require(str(v,"state").equals("DRAFT"),409,"IMMUTABLE_VERSION","已发布版本不可修改，请复制新版本"); }
    /** 模板主记录行锁：保证版本号分配与当前版本指针切换在并发下仍然正确。 */
    void lockTemplate(String templateId) { db.one("SELECT id FROM templates WHERE id=? FOR UPDATE",templateId); }
    /** 自动扫描出的字段默认给一个中文名，避免填报界面和校验提示里出现英文 key；维护员可随时改。 */
    static final Map<String,String> DEFAULT_LABELS=Map.ofEntries(
        Map.entry("contract_no","合同编号"), Map.entry("contract_name","合同名称"), Map.entry("contract_title","合同名称"),
        Map.entry("party_a_name","甲方名称"), Map.entry("party_a_contact","甲方联系人"), Map.entry("party_a_phone","甲方联系电话"), Map.entry("party_a_address","甲方联系地址"),
        Map.entry("party_b_name","乙方名称"), Map.entry("party_b_contact","乙方联系人"), Map.entry("party_b_phone","乙方联系电话"), Map.entry("party_b_address","乙方联系地址"),
        Map.entry("party_a","甲方"), Map.entry("party_b","乙方"), Map.entry("contact","联系人"), Map.entry("phone","联系电话"), Map.entry("address","联系地址"),
        Map.entry("item_name","采购内容"), Map.entry("item_qty","数量"), Map.entry("item_model","型号"), Map.entry("item_spec","规格"),
        Map.entry("unit_price","含税单价（元）"), Map.entry("amount","合同总金额（元）"), Map.entry("amount_upper","金额大写"), Map.entry("tax_included","是否含税"),
        Map.entry("prepay_days","预付款天数"), Map.entry("prepay_ratio","预付款比例（%）"), Map.entry("prepay_amount","预付款金额（元）"),
        Map.entry("final_ratio","尾款比例（%）"), Map.entry("final_amount","尾款金额（元）"), Map.entry("invoice_days","收到发票后付款天数"),
        Map.entry("delivery_date","交付日期"), Map.entry("delivery_address","交付地点"), Map.entry("delivery_days","交付天数"),
        Map.entry("inspect_days","验收天数"), Map.entry("cure_days","整改天数"), Map.entry("warranty_months","质保期（月）"),
        Map.entry("penalty_rate","违约金比例（%/日）"), Map.entry("penalty_cap","违约金上限（%）"), Map.entry("notice_days","催告整改天数"),
        Map.entry("sign_date","签署日期"), Map.entry("start_date","开始日期"), Map.entry("end_date","结束日期"), Map.entry("scope","项目范围"), Map.entry("remark","备注"));
    static String guessLabel(String key) { return DEFAULT_LABELS.getOrDefault(key,key); }
    static boolean isMoneyKey(String key) { return key.contains("amount")||key.contains("price")||key.contains("money")||key.contains("fee")||key.contains("total")||key.endsWith("_rate"); }
    /** 按占位符名字猜一个字段类型（金额/日期/整数/金额大写），猜错也只是默认值，维护员可改。 */
    static String guessType(String key,java.util.Set<String> keys) {
        if(key.endsWith("_upper")&&keys.contains(key.substring(0,key.length()-6))&&isMoneyKey(key.substring(0,key.length()-6))) return "computed";
        if(key.endsWith("_date")||key.endsWith("_on")) return "date";
        if(isMoneyKey(key)) return "money";
        if(key.endsWith("_qty")||key.endsWith("_days")||key.endsWith("_ratio")||key.endsWith("_cap")||key.endsWith("_months")||key.endsWith("_count")||key.endsWith("_num")) return "integer";
        return "text";
    }
    @GetMapping("/templates") Object list(HttpServletRequest r,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        boolean user=str(auth.actor(r),"role").equals("USER"); size=Math.max(1,Math.min(size,100)); page=Math.max(page,1);
        String where=" WHERE t.deleted_at IS NULL AND (LOWER(t.name) LIKE ? OR LOWER(t.code) LIKE ?) AND (?='' OR t.category=?)"+(user?" AND t.status='ACTIVE' AND t.current_version_id IS NOT NULL":"");
        String query="%"+q.toLowerCase(Locale.ROOT)+"%";
        return Map.of("items",db.list("SELECT t.*,u.display_name AS maintainer_name,v.version_no AS current_version_no,(SELECT COUNT(*) FROM template_versions tv WHERE tv.template_id=t.id AND tv.deleted_at IS NULL) AS version_count FROM templates t JOIN users u ON u.id=t.created_by LEFT JOIN template_versions v ON v.id=t.current_version_id"+where+" ORDER BY t.updated_at DESC LIMIT ? OFFSET ?",query,query,category,category,size,(page-1)*size),"total",db.count("SELECT COUNT(*) FROM templates t"+where,query,query,category,category),"page",page,"size",size);
    }
    @PostMapping("/templates") Object create(@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        String code=required(b,"code",50);
        require(code.matches("[A-Za-z0-9_.\\-]{1,50}"),422,"INVALID_CODE","模板编号只能包含字母、数字、点、横线和下划线");
        String id=id(); db.update("INSERT INTO templates(id,code,name,category,description,created_by) VALUES (?,?,?,?,?,?)",id,code,required(b,"name",150),required(b,"category",60),str(b,"description"),auth.actor(r).get("id"));
        db.audit(str(auth.actor(r),"id"),"TEMPLATE_CREATED","TEMPLATE",id,Map.of()); return template(id);
    },"ADMIN","CONTRACT_MAINTAINER"); }
    @GetMapping("/templates/{id}") Object detail(@PathVariable String id,HttpServletRequest r) { maintain(r); var t=template(id); t.put("versions",db.list("SELECT v.*,d.original_name AS docx_file_name,d.byte_size AS docx_file_size,p.original_name AS pdf_file_name,p.byte_size AS pdf_file_size FROM template_versions v LEFT JOIN files d ON d.id=v.docx_file_id LEFT JOIN files p ON p.id=v.source_pdf_file_id WHERE v.template_id=? AND v.deleted_at IS NULL ORDER BY v.version_no DESC",id)); return t; }
    @PatchMapping("/templates/{id}") Object patch(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var t=template(id); lockVersion(t,b); String status=b.containsKey("status")?str(b,"status"):str(t,"status"); require(Set.of("ACTIVE","DISABLED").contains(status),422,"INVALID_STATUS","模板状态无效");
        db.update("UPDATE templates SET name=?,category=?,description=?,status=?,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",b.containsKey("name")?required(b,"name",150):t.get("name"),b.containsKey("category")?required(b,"category",60):t.get("category"),b.getOrDefault("description",t.get("description")),status,id);
        db.audit(str(auth.actor(r),"id"),"TEMPLATE_UPDATED","TEMPLATE",id,Map.of("before_status",t.get("status"),"after_status",status)); return template(id);
    },"ADMIN","CONTRACT_MAINTAINER"); }
    @PostMapping("/templates/{id}/versions") Object newVersion(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        template(id); lockTemplate(id);
        // 版本号规则：必须大于"当前已发布版本号"（保证可发布），并优先复用被删除版本腾出来的号，
        // 例如 V1 已发布、V2 被删除，则下一个新版本仍然是 V2。
        int published=(int)db.count("SELECT COALESCE(MAX(version_no),0) FROM template_versions WHERE template_id=? AND state='PUBLISHED'",id);
        var used=new java.util.HashSet<Integer>(); for(var row:db.list("SELECT version_no FROM template_versions WHERE template_id=?",id)) used.add(num(row,"version_no"));
        int no=published+1; while(used.contains(no)) no++;
        String vid=id();
        Map<String,Object> source=null; if(!str(b,"copy_from").isBlank()) { source=version(str(b,"copy_from")); require(id.equals(source.get("template_id")),422,"INVALID_SOURCE","源版本不属于该模板"); }
        String schema=source==null?"{\"schema_version\":1,\"groups\":[{\"key\":\"base\",\"label\":\"合同信息\",\"order\":1}],\"fields\":[]}":db.stringify(source.get("field_schema"));
        db.update("INSERT INTO template_versions(id,template_id,version_no,docx_file_id,source_pdf_file_id,field_schema,schema_hash,change_note) VALUES (?,?,?,?,?,?,?,?)",vid,id,no,source==null?null:source.get("docx_file_id"),source==null?null:source.get("source_pdf_file_id"),schema,hash(schema),str(b,"change_note"));
        db.audit(str(auth.actor(r),"id"),"VERSION_CREATED","TEMPLATE_VERSION",vid,Map.of("version_no",no)); return version(vid);
    },"ADMIN","CONTRACT_MAINTAINER"); }
    /** 给版本命名/改说明：纯元数据，不参与渲染指纹，因此不提升 lock_version、不影响已完成的试填确认。 */
    @PatchMapping("/template-versions/{id}/note") Object rename(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{        var v=version(id); String note=str(b,"change_note"); require(note.length()<=200,422,"INVALID_INPUT","版本名称不能超过 200 字");
        db.update("UPDATE template_versions SET change_note=? WHERE id=?",note,id);
        db.audit(str(auth.actor(r),"id"),"VERSION_RENAMED","TEMPLATE_VERSION",id,Map.of("before",str(v,"change_note"),"after",note));
        return version(id);
    },"ADMIN","CONTRACT_MAINTAINER"); }
    /**
     * 一键把"显示名还是英文 key"的字段改成内置中文名。
     * 只改 label（显示名），不动类型、必填和取值绑定，也不影响生成出来的正文，
     * 因此已发布版本也允许执行（填报界面与校验提示立刻变成中文）。
     */
    @PostMapping("/template-versions/{id}/labels") Object fillLabels(@PathVariable String id,HttpServletRequest r) { return auth.write(r,()->{
        var v=version(id); var schema=obj(v,"field_schema"); var fs=fields(schema);
        require(!fs.isEmpty(),422,"INVALID_SCHEMA","该版本还没有字段配置，请先上传母版或粘贴字段配置");
        int changed=0;
        for(var f:fs){
            String key=str(f,"key"), label=str(f,"label");
            if(label.isBlank()||label.equals(key)) { String cn=guessLabel(key); if(!cn.equals(label)) { f.put("label",cn); changed++; } }
        }
        if(changed>0) { String json=db.stringify(schema); db.update("UPDATE template_versions SET field_schema=?,schema_hash=? WHERE id=?",json,hash(json),id); }
        db.audit(str(auth.actor(r),"id"),"VERSION_LABELS_FILLED","TEMPLATE_VERSION",id,Map.of("changed",changed));
        var out=new LinkedHashMap<String,Object>(version(id)); out.put("changed",changed); return out;
    },"ADMIN","CONTRACT_MAINTAINER"); }
    /**
     * 按母版重新对齐字段配置：母版里新增的占位符补进配置，母版里已不存在的字段从配置里删掉。
     * 用于"复制了版本/换了一份母版"之后一次性修好绑定，不必手工改 JSON。
     * 只作用于草稿版本（已发布版本的母版与字段都不可改）。
     */
    @PostMapping("/template-versions/{id}/sync-fields") Object syncFieldsWithMaster(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var v=version(id); draft(v); lockVersion(v,b);
        require(v.get("docx_file_id")!=null,422,"DOCX_REQUIRED","请先上传 DOCX 母版，字段以母版为准");
        var found=engine.scan(files.read(str(v,"docx_file_id")));
        require(!found.isEmpty(),422,"NO_PLACEHOLDER","母版中未找到任何 {{占位符}}，无法按母版同步字段");
        var schema=obj(v,"field_schema"); var removed=new ArrayList<String>(); var addedRef=new int[1]; var repaired=new ArrayList<String>();
        var before=new ArrayList<>(fields(schema));
        var synced=syncFields(schema,found.keySet(),null,removed,addedRef,repaired);
        var removedDefs=before.stream().filter(f->removed.contains(str(f,"key"))).toList();
        if(synced!=null) { String json=db.stringify(synced); db.update("UPDATE template_versions SET field_schema=?,schema_hash=?,lock_version=lock_version+1,reviewed_fingerprint=NULL,test_job_id=NULL WHERE id=?",json,hash(json),id); }
        db.audit(str(auth.actor(r),"id"),"VERSION_FIELDS_SYNCED","TEMPLATE_VERSION",id,Map.of("added",addedRef[0],"removed",removed.size(),"removed_keys",removed,"removed_fields",removedDefs,"repaired_fields",repaired));
        var out=new LinkedHashMap<String,Object>(version(id)); out.put("changed",synced!=null); out.put("addedFields",addedRef[0]); out.put("removedFields",removed.size()); out.put("removedFieldKeys",removed); out.put("repairedFields",repaired.size()); out.put("repairedFieldNotes",repaired); return out;
    },"ADMIN","CONTRACT_MAINTAINER"); }
    /** 按占位符集合生成字段定义（中文名优先用字典，auto_N 用识别时的上下文）。 */
    List<Map<String,Object>> fieldMaps(java.util.Set<String> keys,List<String> contexts,int startOrder) {
        var fs=new ArrayList<Map<String,Object>>(); int order=startOrder;
        for(String key:keys){
            var fm=new LinkedHashMap<String,Object>();
            String type=guessType(key,keys);
            String label=guessLabel(key);
            if(label.equals(key)&&contexts!=null) {                      // auto_N → 用标记前面的文字当默认名
                int idx=-1; try{ idx=Integer.parseInt(key.substring(key.indexOf('_')+1))-1; }catch(Exception ignored){ }
                if(idx>=0&&idx<contexts.size()&&!contexts.get(idx).isBlank()) label=contexts.get(idx);
            }
            fm.put("key",key); fm.put("label",label); fm.put("type",type); fm.put("group","base");
            fm.put("required",false); fm.put("binding",key); fm.put("order",++order);
            if(type.equals("computed")) { fm.put("calculator","rmb_upper"); fm.put("source",key.substring(0,key.length()-6)); }
            else if(type.equals("money")) { fm.put("scale",2); fm.put("min","0.00"); fm.put("max","999999999.99"); }
            else if(type.equals("integer")) { fm.put("min",0); fm.put("max","999999999"); }
            else if(type.equals("date")) { fm.put("format","chinese"); }
            else { fm.put("max_length",200); }
            fs.add(fm);
        }
        return fs;
    }
    Map<String,Object> autoFields(java.util.Set<String> keys,List<String> contexts) {
        var schema=new LinkedHashMap<String,Object>();
        schema.put("schema_version",1); schema.put("groups",new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("key","base","label","合同信息","order",1)))));
        schema.put("fields",fieldMaps(keys,contexts,0)); return schema;
    }
    /**
     * 让字段配置与母版对齐（母版是字段的唯一来源）：
     * ① 母版里新出现、配置里还没有的占位符 → 自动补一条（保留原有字段的顺序与设置）；
     * ② 配置里有、母版里已经不存在的字段 → 自动移除。否则试填/发布会报"字段绑定不匹配：母版中不存在 …"；
     * ③ 顺手修复被连带弄坏的引用（金额大写指向了一个已被删掉的金额字段、日期先后引用了不存在的日期、
     *    字段挂到了不存在的分组），这些会让字段配置本身通不过校验。
     * 只处理绑定到母版的字段（binding=null 的字段本来就不参与绑定校验）。
     * 返回 null 表示无需改动。
     */
    @SuppressWarnings("unchecked")
    Map<String,Object> syncFields(Map<String,Object> schema,java.util.Set<String> tokens,List<String> contexts,List<String> removedOut,int[] addedOut,List<String> repairedOut) {
        var list=(List<Map<String,Object>>)schema.computeIfAbsent("fields",k->new ArrayList<Map<String,Object>>());
        var kept=new ArrayList<Map<String,Object>>(); var keptKeys=new HashSet<String>();
        for(var f:list) {
            String key=str(f,"key"); boolean bound=!f.containsKey("binding")||f.get("binding")!=null;
            if(bound&&!tokens.contains(key)) { removedOut.add(key); continue; }
            kept.add(f); keptKeys.add(key);
        }
        var missing=new LinkedHashSet<String>(); for(String k:tokens) if(!keptKeys.contains(k)) missing.add(k);
        int maxOrder=0; for(var f:kept) maxOrder=Math.max(maxOrder,num(f,"order"));
        var added=fieldMaps(missing,contexts,maxOrder);
        kept.addAll(added);
        repairedOut.addAll(repairReferences(schema,kept));
        if(removedOut.isEmpty()&&added.isEmpty()&&repairedOut.isEmpty()) { addedOut[0]=0; return null; }
        list.clear(); list.addAll(kept);
        addedOut[0]=added.size(); return schema;
    }
    /**
     * 修复字段配置里被"删字段"连带弄坏的引用，保证配置本身能通过 FieldRules 校验。
     * 金额大写指向的金额字段没了：只有一个同族金额字段（amount_upper → amount / 唯一的 amount_*）时自动接上，
     * 多个候选就不猜（大写填错是钱的问题），降级成手填金额字段。
     */
    List<String> repairReferences(Map<String,Object> schema,List<Map<String,Object>> all) {
        var byKey=new LinkedHashMap<String,Map<String,Object>>(); for(var f:all) byKey.put(str(f,"key"),f);
        var groups=new LinkedHashSet<String>();
        var groupList=schema.get("groups") instanceof List<?> gl?gl:List.of();
        for(Object g:groupList) if(g instanceof Map<?,?> m&&m.get("key") instanceof String k) groups.add(k);
        var firstGroup=groups.isEmpty()?null:groups.iterator().next();
        var fixed=new ArrayList<String>();
        for(var f:all) {
            String key=str(f,"key"),type=str(f,"type");
            if(firstGroup!=null&&(!f.containsKey("group")||!groups.contains(str(f,"group")))) { f.put("group",firstGroup); fixed.add(key+"：分组归入 "+firstGroup); }
            if(type.equals("computed")) {
                var source=byKey.get(str(f,"source"));
                if(source==null||!str(source,"type").equals("money")) {
                    String candidate=singleMoneySource(key,all,byKey);
                    if(candidate!=null) { String before=str(f,"source"); f.put("source",candidate); fixed.add(key+"：金额大写来源 "+before+" → "+candidate); }
                    else {
                        f.put("type","money"); f.remove("calculator"); f.remove("source"); f.remove("default");
                        f.put("scale",2); f.put("min","0.00"); f.put("max","999999999.99");
                        fixed.add(key+"：原金额来源已不存在，改为手填金额字段");
                    }
                }
            } else if(type.equals("date")&&!str(f,"after").isBlank()) {
                var after=byKey.get(str(f,"after"));
                if(after==null||!str(after,"type").equals("date")) { f.remove("after"); fixed.add(key+"：取消日期先后校验"); }
            }
        }
        return fixed;
    }
    /** 只在"唯一候选"时自动接上金额来源，避免把大写金额接到错误的金额字段上。 */
    String singleMoneySource(String key,List<Map<String,Object>> all,Map<String,Map<String,Object>> byKey) {
        String base=key.replaceAll("_(upper|capital|rmb|chinese)$","");
        if(!base.equals(key)) {
            var exact=byKey.get(base);
            if(exact!=null&&str(exact,"type").equals("money")) return base;
            var family=new ArrayList<String>();
            for(var f:all) if(str(f,"type").equals("money")&&str(f,"key").startsWith(base+"_")) family.add(str(f,"key"));
            if(family.size()==1) return family.getFirst();
        }
        return null;
    }
    @PostMapping("/template-versions/{id}/files") Object upload(@PathVariable String id,@RequestParam MultipartFile file,@RequestParam int expected_lock_version,@RequestParam(defaultValue="FF0000") String marker_color,HttpServletRequest r) {
        // 解压/结构校验/落盘都在全局锁之外：这些是毫秒到秒级操作，放在锁内会阻塞全系统写请求。
        var pre=version(id); draft(pre); lockVersion(pre,Map.of("expected_lock_version",expected_lock_version));
        String markerHex=marker_color==null?"":marker_color.trim().replace("#","").toUpperCase(Locale.ROOT);
        require(markerHex.matches("[0-9A-F]{6}"),422,"INVALID_COLOR","标记色必须是 6 位十六进制色值，例如 FF0000");
        byte[] bytes; try { bytes=file.getBytes(); } catch(java.io.IOException e) { throw new ApiException(422,"UPLOAD_FAILED","无法读取上传文件"); }
        var uploaded=files.upload(file,"TEMPLATE",str(auth.actor(r),"id"),null); boolean pdf=str(uploaded,"mime_type").equals("application/pdf");
        Map<String,Object> auto=null; String warn=null; Map<String,Object> master=uploaded; Map<String,Object> original=null; int marks=0,colorMarks=0,added=0;
        var addedRef=new int[1]; var removed=new ArrayList<String>(); var repaired=new ArrayList<String>(); List<Map<String,Object>> removedDefs=List.of();
        if(!pdf) {
            var candidates=engine.scan(bytes);
            java.util.Set<String> tokens; List<String> contexts=null; byte[] normalized=null;
            if(candidates.isEmpty()) {
                // 没有 {{占位符}} 时自动识别：① 用指定颜色标记的文字 ② 连续下划线空白，改写成占位符（原始上传件单独存档）
                var colorNorm=engine.normalizeColor(bytes,markerHex,0);
                @SuppressWarnings("unchecked") var colorCtx=(List<String>)colorNorm.get("contexts");
                colorMarks=colorCtx.size();
                var blankNorm=engine.normalizeBlanks((byte[])colorNorm.get("docx"),colorMarks);
                @SuppressWarnings("unchecked") var blankCtx=(List<String>)blankNorm.get("contexts");
                marks=colorMarks+blankCtx.size();
                if(marks==0) {
                    warn="母版中未找到 {{占位符}}，也没有识别到标记：请把要填写的位置写成 {{字段名}}，或用 3 个以上连续下划线（___）标出空白，或把待填文字设成标记色（当前 "+markerHex+"）后重新上传";
                    tokens=null;
                } else {
                    contexts=new ArrayList<String>(); contexts.addAll(colorCtx); contexts.addAll(blankCtx);
                    normalized=(byte[])blankNorm.get("docx");
                    tokens=engine.scan(normalized).keySet();
                }
            } else { tokens=candidates.keySet(); }
            if(tokens!=null) {
                if(normalized!=null) { master=files.store(normalized,"contract-normalized.docx","docx","TEMPLATE_NORMALIZED",str(auth.actor(r),"id")); original=uploaded; }
                var schema=obj(pre,"field_schema");
                var before=fields(schema).isEmpty()?List.<Map<String,Object>>of():new ArrayList<>(fields(schema));
                if(fields(schema).isEmpty()) auto=autoFields(tokens,contexts);
                else auto=syncFields(schema,tokens,contexts,removed,addedRef,repaired);   // 母版变了就按母版增删字段，避免绑定不匹配
                added=addedRef[0];
                // 被删掉的字段定义整条记进审计：误删时可以从审计里查回来重新粘进字段配置。
                removedDefs=before.stream().filter(f->removed.contains(str(f,"key"))).toList();
            }
        }
        final Map<String,Object> autoSchema=auto, masterFile=master, originalFile=original; final String warning=warn;
        final int marksFound=marks, colorMarksFound=colorMarks, addedFound=added;
        final List<String> removedFound=List.copyOf(removed);
        final List<String> repairedFound=List.copyOf(repaired);
        final List<Map<String,Object>> removedDefsFound=List.copyOf(removedDefs);
        return auth.write(r,()->{
            var v=version(id); draft(v); lockVersion(v,Map.of("expected_lock_version",expected_lock_version));
            if(pdf) db.update("UPDATE template_versions SET source_pdf_file_id=?,lock_version=lock_version+1,reviewed_fingerprint=NULL,test_job_id=NULL WHERE id=?",uploaded.get("id"),id);
            else if(originalFile!=null) db.update("UPDATE template_versions SET docx_file_id=?,original_docx_file_id=?,lock_version=lock_version+1,reviewed_fingerprint=NULL,test_job_id=NULL WHERE id=?",masterFile.get("id"),originalFile.get("id"),id);
            else db.update("UPDATE template_versions SET docx_file_id=?,original_docx_file_id=NULL,lock_version=lock_version+1,reviewed_fingerprint=NULL,test_job_id=NULL WHERE id=?",masterFile.get("id"),id);
            if(autoSchema!=null) { String json=db.stringify(autoSchema); db.update("UPDATE template_versions SET field_schema=?,schema_hash=? WHERE id=?",json,hash(json),id); }
            db.audit(str(auth.actor(r),"id"),"TEMPLATE_UPLOADED","TEMPLATE_VERSION",id,Map.of("sha256",uploaded.get("sha256"),"format",pdf?"pdf":"docx","auto_marks",marksFound,"auto_color_marks",colorMarksFound,"marker_color",markerHex,"added_fields",addedFound,"removed_fields",removedDefsFound,"repaired_fields",repairedFound));
            var out=new LinkedHashMap<String,Object>(version(id));
            if(warning!=null) out.put("warning",warning);
            if(marksFound>0) { out.put("autoFields",marksFound); out.put("autoColorFields",colorMarksFound); out.put("autoBlankFields",marksFound-colorMarksFound); }
            if(addedFound>0) out.put("addedFields",addedFound);
            if(!repairedFound.isEmpty()) { out.put("repairedFields",repairedFound.size()); out.put("repairedFieldNotes",repairedFound); }
            if(!removedFound.isEmpty()) { out.put("removedFields",removedFound.size()); out.put("removedFieldKeys",removedFound); }
            return out;
        },"ADMIN","CONTRACT_MAINTAINER");
    }
    @GetMapping("/template-versions/{id}") Object getVersion(@PathVariable String id,HttpServletRequest r) { maintain(r); var v=version(id); if(v.get("docx_file_id")!=null) v.put("occurrences",engine.scan(files.read(str(v,"docx_file_id")))); return v; }
    @GetMapping("/template-versions/{id}/form-schema") Object schema(@PathVariable String id,HttpServletRequest r) {
        var v=version(id); var t=template(str(v,"template_id"));
        // 模板"停用"只阻止新建合同；存量草稿仍须能读取填报配置，否则在途草稿会被永久锁死。
        if(str(auth.actor(r),"role").equals("USER")) require(str(v,"state").equals("PUBLISHED"),404,"NOT_FOUND","模板不可用");
        return v.get("field_schema");
    }
    @PutMapping("/template-versions/{id}/schema") Object saveSchema(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var v=version(id); draft(v); lockVersion(v,b); var schema=obj(b,"field_schema"); rules.schema(schema); String json=db.stringify(schema);
        var migration=obj(b,"migration_map"); for(var e:migration.entrySet()) require(e.getValue() instanceof String&&fields(schema).stream().anyMatch(f->str(f,"key").equals(e.getValue())),422,"INVALID_MAPPING","迁移映射目标字段不存在");
        require(new HashSet<>(migration.values()).size()==migration.size(),422,"INVALID_MAPPING","迁移映射目标不能重复");
        db.update("UPDATE template_versions SET field_schema=?,schema_hash=?,change_note=?,migration_map=?,lock_version=lock_version+1,reviewed_fingerprint=NULL,test_job_id=NULL WHERE id=?",json,hash(json),b.getOrDefault("change_note",v.get("change_note")),db.stringify(migration),id);
        db.audit(str(auth.actor(r),"id"),"SCHEMA_UPDATED","TEMPLATE_VERSION",id,Map.of("schema_hash",hash(json))); return version(id);
    },"ADMIN","CONTRACT_MAINTAINER"); }
    Map<String,Object> validateVersion(String id) {
        var v=version(id);
        require(v.get("docx_file_id")!=null,422,"DOCX_REQUIRED","请先上传 DOCX 母版；PDF 只能作为参考原件");
        byte[] bytes=files.read(str(v,"docx_file_id"));
        // 先确认母版里真的存在占位符：否则只报"字段配置必须包含 1～150 个字段"，维护员不知道要改母版。
        var found=engine.scan(bytes);
        require(!found.isEmpty(),422,"NO_PLACEHOLDER","母版中未找到任何 {{占位符}}。请在 Word 中把需要填写的内容改写成 {{字段名}}（例如 {{party_b_name}}、{{amount}}）后重新上传 DOCX。");
        rules.schema(obj(v,"field_schema"));
        return engine.validate(bytes,obj(v,"field_schema"));
    }
    @PostMapping("/template-versions/{id}/validate") Object validate(@PathVariable String id,HttpServletRequest r) { return auth.write(r,()->Map.of("valid",true,"occurrences",validateVersion(id)),"ADMIN","CONTRACT_MAINTAINER"); }
    @PostMapping("/template-versions/{id}/test-render") @ResponseStatus(HttpStatus.ACCEPTED) Object test(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var v=version(id); draft(v); lockVersion(v,b); validateVersion(id); var values=rules.validate(obj(v,"field_schema"),obj(b,"form_data"),true);
        var job=generation.enqueue(v,values,auth.actor(r),null,"test:"+id+":"+hash(generation.fingerprint(v)+db.stringify(values)+auth.actor(r).get("id")));
        db.update("UPDATE template_versions SET test_job_id=?,reviewed_fingerprint=NULL WHERE id=?",job.get("id"),id); return job;
    },"ADMIN","CONTRACT_MAINTAINER"); }
    /** 打开试填生成的 PDF：既让维护员核对版式，也记录"已查看"，试填确认才有依据。 */
    @GetMapping("/template-versions/{id}/test-preview") Object testPreview(@PathVariable String id,HttpServletRequest r) {
        maintain(r); var v=version(id);
        var job=db.one("SELECT * FROM generation_jobs WHERE id=? AND state='SUCCEEDED'",v.get("test_job_id"));
        String sha=str(db.one("SELECT sha256 FROM files WHERE id=?",job.get("pdf_file_id")),"sha256");
        db.recordPreviewView(str(auth.actor(r),"id"),str(job,"id"),sha);
        return download(str(job,"pdf_file_id"),"模板试填-V"+v.get("version_no")+".pdf",true,r,"TEMPLATE_TEST_PREVIEWED",id);
    }
    @PostMapping("/template-versions/{id}/test-review") Object review(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var v=version(id); draft(v); require(Boolean.TRUE.equals(b.get("confirmed")),422,"CONFIRMATION_REQUIRED","请确认已检查全文与版式");
        var job=db.one("SELECT * FROM generation_jobs WHERE id=? AND state='SUCCEEDED'",v.get("test_job_id")); require(generation.fingerprint(v).equals(job.get("fingerprint")),409,"TEST_OUTDATED","母版或字段已变更，请重新试填");
        String pdfHash=str(db.one("SELECT sha256 FROM files WHERE id=?",job.get("pdf_file_id")),"sha256");
        require(db.count("SELECT COUNT(*) FROM preview_views WHERE user_id=? AND job_id=? AND pdf_sha256=?",auth.actor(r).get("id"),job.get("id"),pdfHash)>0,422,"PREVIEW_REQUIRED","请先打开本次生成的完整 PDF");
        db.update("UPDATE template_versions SET reviewed_fingerprint=?,reviewed_by=?,reviewed_at=? WHERE id=?",job.get("fingerprint"),auth.actor(r).get("id"),at(Instant.now()),id);
        db.audit(str(auth.actor(r),"id"),"TEMPLATE_REVIEWED","TEMPLATE_VERSION",id,Map.of("pdf_sha256",pdfHash)); return version(id);
    },"ADMIN","CONTRACT_MAINTAINER"); }
    @PostMapping("/template-versions/{id}/publish") Object publish(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var v=version(id); draft(v); lockVersion(v,b); validateVersion(id); var t=template(str(v,"template_id"));
        require(str(t,"status").equals("ACTIVE"),409,"TEMPLATE_DISABLED","请先启用模板");
        require(generation.fingerprint(v).equals(v.get("reviewed_fingerprint")),409,"TEST_REVIEW_REQUIRED","发布前必须完成当前母版及字段配置的试填和全文确认");
        lockTemplate(str(t,"id"));
        int current=t.get("current_version_no")==null?0:((Number)t.get("current_version_no")).intValue(); require(num(v,"version_no")>current,409,"VERSION_ORDER","该草稿版本早于当前发布版本，请复制为新版本后发布");
        db.update("UPDATE template_versions SET state='PUBLISHED',published_at=? WHERE id=?",at(Instant.now()),id);
        db.update("UPDATE templates SET current_version_id=?,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id,t.get("id"));
        db.audit(str(auth.actor(r),"id"),"TEMPLATE_PUBLISHED","TEMPLATE",str(t,"id"),Map.of("version_no",v.get("version_no"),"previous_version",current)); return version(id);
    },"ADMIN","CONTRACT_MAINTAINER"); }
    /**
     * 彻底删除版本草稿（物理删除）：删除版本行、它的试填任务与试填产物，
     * 并回收只被它引用的母版/原件文件——版本号因此被释放，下一个新版本可以重新使用该号。
     * 只允许删除草稿，且必须没有被任何合同引用、没有运行中的任务。
     */
    @DeleteMapping("/template-versions/{id}") Object deleteVersion(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var v=version(id); draft(v); lockVersion(v,b); String reason=required(b,"reason",1000);
        require(db.count("SELECT COUNT(*) FROM contracts WHERE template_version_id=?",id)==0,409,"VERSION_IN_USE","该版本已被合同引用，不能删除；请停用模板或复制新版本");
        require(db.count("SELECT COUNT(*) FROM generation_jobs WHERE template_version_id=? AND state IN ('QUEUED','RUNNING')",id)==0,409,"VERSION_IN_USE","该版本仍有运行中的生成任务，请稍后再删除");
        int no=num(v,"version_no");
        var jobFiles=db.list("SELECT DISTINCT f.id FROM files f WHERE f.id IN (SELECT docx_file_id FROM generation_jobs WHERE template_version_id=?) OR f.id IN (SELECT pdf_file_id FROM generation_jobs WHERE template_version_id=?)",id,id);
        long jobs=db.count("SELECT COUNT(*) FROM generation_jobs WHERE template_version_id=?",id);
        db.update("DELETE FROM preview_views WHERE job_id IN (SELECT id FROM generation_jobs WHERE template_version_id=?)",id);
        db.update("DELETE FROM generation_jobs WHERE template_version_id=?",id);
        String docxFile=str(v,"docx_file_id"), pdfFile=str(v,"source_pdf_file_id"), originalFile=str(v,"original_docx_file_id");
        db.update("DELETE FROM template_versions WHERE id=?",id);
        long filesPurged=0;
        for(var f:jobFiles) if(files.purgeIfUnreferenced(str(f,"id"))) filesPurged++;
        if(files.purgeIfUnreferenced(docxFile)) filesPurged++;
        if(files.purgeIfUnreferenced(pdfFile)) filesPurged++;
        if(files.purgeIfUnreferenced(originalFile)) filesPurged++;
        db.audit(str(auth.actor(r),"id"),"VERSION_PURGED","TEMPLATE_VERSION",id,Map.of("reason",reason,"version_no",no,"jobs",jobs,"files",filesPurged));
        return Map.of("ok",true,"version_no",no,"jobs",jobs,"files",filesPurged,"freed_version_no",no);
    },"ADMIN","CONTRACT_MAINTAINER"); }
    @DeleteMapping("/templates/{id}") Object delete(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return auth.write(r,()->{
        var t=template(id); lockVersion(t,b); String reason=required(b,"reason",1000);
        // 彻底删除模板：级联删除它的全部版本（含已发布版本）、试填任务与只被它引用的母版/原件文件。
        // 唯一保留的硬约束：不能有合同引用（任何状态，包括已作废的），否则历史合同会失去模板归属。
        require(db.count("SELECT COUNT(*) FROM contracts WHERE template_id=?",id)==0,409,"TEMPLATE_IN_USE","已有合同引用该模板，不能删除；可改用停用（停用后不影响历史合同）");
        require(db.count("SELECT COUNT(*) FROM generation_jobs j JOIN template_versions v ON v.id=j.template_version_id WHERE v.template_id=? AND j.state IN ('QUEUED','RUNNING')",id)==0,409,"TEMPLATE_IN_USE","该模板仍有运行中的生成任务，请稍后再删除");
        String code=str(t,"code");
        var versions=db.list("SELECT id,docx_file_id,source_pdf_file_id,original_docx_file_id FROM template_versions WHERE template_id=?",id);
        var jobFiles=db.list("SELECT DISTINCT f.id FROM files f WHERE f.id IN (SELECT docx_file_id FROM generation_jobs WHERE template_version_id IN (SELECT id FROM template_versions WHERE template_id=?)) OR f.id IN (SELECT pdf_file_id FROM generation_jobs WHERE template_version_id IN (SELECT id FROM template_versions WHERE template_id=?))",id,id);
        long jobs=db.count("SELECT COUNT(*) FROM generation_jobs WHERE template_version_id IN (SELECT id FROM template_versions WHERE template_id=?)",id);
        db.update("UPDATE templates SET current_version_id=NULL WHERE id=?",id);
        db.update("DELETE FROM preview_views WHERE job_id IN (SELECT id FROM generation_jobs WHERE template_version_id IN (SELECT id FROM template_versions WHERE template_id=?))",id);
        db.update("DELETE FROM generation_jobs WHERE template_version_id IN (SELECT id FROM template_versions WHERE template_id=?)",id);
        db.update("DELETE FROM template_versions WHERE template_id=?",id);
        db.update("DELETE FROM templates WHERE id=?",id);
        long filesPurged=0;
        for(var f:jobFiles) if(files.purgeIfUnreferenced(str(f,"id"))) filesPurged++;
        for(var v:versions){ if(files.purgeIfUnreferenced(str(v,"docx_file_id"))) filesPurged++; if(files.purgeIfUnreferenced(str(v,"source_pdf_file_id"))) filesPurged++; if(files.purgeIfUnreferenced(str(v,"original_docx_file_id"))) filesPurged++; }
        db.audit(str(auth.actor(r),"id"),"TEMPLATE_PURGED","TEMPLATE",id,Map.of("reason",reason,"code",code,"versions",versions.size(),"jobs",jobs,"files",filesPurged));
        return Map.of("ok",true,"code",code,"versions",versions.size(),"jobs",jobs,"files",filesPurged);
    },"ADMIN","CONTRACT_MAINTAINER"); }
    @GetMapping("/templates/{id}/current-source") Object source(@PathVariable String id,HttpServletRequest r) { maintain(r); var t=template(id); require(t.get("current_version_id")!=null,404,"NOT_FOUND","模板尚未发布"); var v=version(str(t,"current_version_id")); return download(str(v,"docx_file_id"),str(t,"code")+"-V"+v.get("version_no")+".docx",false,r,"TEMPLATE_SOURCE",id); }
    @GetMapping("/template-versions/{id}/source") Object versionSource(@PathVariable String id,@RequestParam(defaultValue="docx") String format,HttpServletRequest r) {
        maintain(r); var v=version(id); require(Set.of("docx","pdf","original").contains(format),422,"INVALID_FORMAT","文件格式无效");
        if(format.equals("original")) { require(v.get("original_docx_file_id")!=null,404,"NOT_FOUND","该版本没有单独的原始上传件（母版本身就是原始文件）");
            return download(str(v,"original_docx_file_id"),"模板原始上传件-V"+v.get("version_no")+".docx",false,r,"TEMPLATE_SOURCE_ORIGINAL",id); }
        return download(str(v,format.equals("pdf")?"source_pdf_file_id":"docx_file_id"),"模板-V"+v.get("version_no")+"."+format,false,r,"TEMPLATE_SOURCE",id);
    }
    ResponseEntity<byte[]> download(String fileId,String name,boolean inline,HttpServletRequest r,String action,String objectId) {
        var file=db.one("SELECT * FROM files WHERE id=?",fileId); byte[] bytes=files.read(fileId);
        db.audit(str(auth.actor(r),"id"),action,"FILE",objectId,Map.of("sha256",file.get("sha256")));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(str(file,"mime_type"))).header("Content-Disposition",(inline?ContentDisposition.inline():ContentDisposition.attachment()).filename(name,StandardCharsets.UTF_8).build().toString()).header("X-Content-SHA256",str(file,"sha256")).body(bytes);
    }
}
