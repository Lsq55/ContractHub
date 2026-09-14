package cn.qiheng.contracthub;

import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static cn.qiheng.contracthub.Db.*;
import static cn.qiheng.contracthub.ApiException.require;

@Service
public class FileStore {
    final Db db; final DocumentEngine engine; final Path root; final String scanCommand; final long maxBytes;
    FileStore(Db db,DocumentEngine engine,@Value("${app.storage}") String root,@Value("${app.scan-command}") String scanCommand,@Value("${spring.servlet.multipart.max-file-size}") String max) throws IOException {
        this.db=db; this.engine=engine; this.root=Path.of(root).toAbsolutePath().normalize(); this.scanCommand=scanCommand;
        this.maxBytes=org.springframework.util.unit.DataSize.parse(max).toBytes(); Files.createDirectories(this.root);
    }
    Map<String,Object> upload(MultipartFile file,String purpose,String actor,String requiredType) {
        require(file!=null&&!file.isEmpty()&&file.getSize()<=maxBytes,413,"INVALID_FILE_SIZE","文件为空或超过上传限制");
        String name=Objects.toString(file.getOriginalFilename(),"file"); String ext=name.toLowerCase(Locale.ROOT).endsWith(".docx")?"docx":name.toLowerCase(Locale.ROOT).endsWith(".pdf")?"pdf":"";
        require(!ext.isBlank()&&(requiredType==null||requiredType.equals(ext)),415,"UNSUPPORTED_FILE","仅支持 DOCX 母版和 PDF 文件");
        String mime=ext.equals("pdf")?"application/pdf":"application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        require(Set.of(mime,"application/octet-stream","application/zip").contains(Objects.toString(file.getContentType(),"application/octet-stream")),415,"MIME_MISMATCH","文件类型与扩展名不一致");
        try { byte[] bytes=file.getBytes(); if(ext.equals("docx")) engine.scan(bytes); else checkPdf(bytes,100); scan(bytes,ext); return store(bytes,name,ext,purpose,actor); }
        catch(IOException e) { throw new ApiException(422,"UPLOAD_FAILED","无法读取上传文件"); }
    }
    void checkPdf(byte[] bytes,int maxPages) {
        require(bytes.length>5&&new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"),415,"INVALID_PDF","文件不是有效 PDF");
        try(var doc=Loader.loadPDF(bytes)) {
            require(!doc.isEncrypted()&&doc.getNumberOfPages()>0&&doc.getNumberOfPages()<=maxPages,422,"INVALID_PDF","PDF 已加密或页数超出限制");
            inspectPdf(doc.getDocumentCatalog().getCOSObject(),Collections.newSetFromMap(new IdentityHashMap<>()));
        } catch(ApiException e) { throw e; } catch(Exception e) {
            var stack=e.getStackTrace();
            String where=stack.length==0?"":(stack[0].getClassName()+"#"+stack[0].getMethodName()+"("+stack[0].getLineNumber()+")");
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("PDF 解析失败 bytes={} cause={} at {}",bytes.length,e.toString(),where);
            String cause=e.getClass().getSimpleName()+(e.getMessage()==null?"":(" "+e.getMessage()));
            if(cause.length()>120) cause=cause.substring(0,120);
            throw new ApiException(422,"INVALID_PDF","PDF 已损坏或无法解析（"+bytes.length+" 字节）："+cause+" @"+where);
        }
    }
    void inspectPdf(org.apache.pdfbox.cos.COSBase item,Set<org.apache.pdfbox.cos.COSBase> visited) {
        if(item==null||!visited.add(item)) return;
        require(visited.size()<100000,422,"PDF_LIMIT","PDF 对象数超出限制");
        if(item instanceof org.apache.pdfbox.cos.COSObject o) inspectPdf(o.getObject(),visited);
        else if(item instanceof org.apache.pdfbox.cos.COSArray a) for(var v:a) inspectPdf(v,visited);
        else if(item instanceof org.apache.pdfbox.cos.COSDictionary d) {
            for(String key:List.of("JS","JavaScript","Launch","EmbeddedFiles","OpenAction","AA","RichMediaContent")) require(!d.containsKey(key),422,"UNSAFE_PDF","PDF 含主动内容或嵌入文件，请扁平化后上传");
            // 注意：不能用 COSDictionary#getNameAsString("S")，缺少 S 项时 PDFBox 3.x 会抛空指针，
            // 导致所有正常 PDF 都被误判为"损坏"（生成与 PDF 上传统统失败）。
            var action=d.getDictionaryObject("S");
            require(!(action instanceof org.apache.pdfbox.cos.COSName name)||!List.of("URI","GoToR","Launch","SubmitForm","ImportData").contains(name.getName()),422,"UNSAFE_PDF","PDF 含外部链接或主动动作");
            for(var v:d.getValues()) inspectPdf(v,visited);
        }
    }
    /**
     * 把 SCAN_COMMAND 拆成"可执行文件 + 参数"。支持空格分隔，双引号内可含空格
     * （例如 "C:\Program Files\x\scan.exe" --quiet %FILE%）。
     * 早期实现把整个字符串当成可执行文件名，配了参数的扫描命令一律启动失败。
     */
    static List<String> commandTokens(String command) {
        var out=new ArrayList<String>(); var sb=new StringBuilder(); boolean quoted=false;
        for(char c:command.trim().toCharArray()) {
            if(c=='"') { quoted=!quoted; continue; }
            if(!quoted&&Character.isWhitespace(c)) { if(sb.length()>0) { out.add(sb.toString()); sb.setLength(0); } continue; }
            sb.append(c);
        }
        if(sb.length()>0) out.add(sb.toString());
        return out;
    }
    void scan(byte[] bytes,String ext) throws IOException {
        if(scanCommand.isBlank()) return;
        Path temp=Files.createTempFile(root,"scan-","."+ext);
        try {
            Files.write(temp,bytes);
            var argv=new ArrayList<>(commandTokens(scanCommand));
            if(argv.isEmpty()) return;
            argv.add(temp.toString());
            Process p;
            try { p=new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); }
            catch(IOException e) { throw new ApiException(422,"FILE_SCAN_FAILED","安全扫描程序无法启动，请检查 SCAN_COMMAND 配置："+argv.getFirst()); }
            boolean finished=p.waitFor(60,TimeUnit.SECONDS); if(!finished) p.destroyForcibly();
            require(finished&&p.exitValue()==0,422,"FILE_SCAN_FAILED","文件未通过安全扫描");
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } finally { Files.deleteIfExists(temp); }
    }
    Map<String,Object> store(byte[] bytes,String name,String ext,String purpose,String actor) {
        String id=id(),key=id+"."+ext; Path temp=null;
        try { temp=Files.createTempFile(root,"write-",".tmp"); Files.write(temp,bytes); Files.move(temp,root.resolve(key),StandardCopyOption.ATOMIC_MOVE);
            db.update("INSERT INTO files(id,storage_key,original_name,mime_type,byte_size,sha256,purpose,created_by) VALUES (?,?,?,?,?,?,?,?)",id,key,name,ext.equals("pdf")?"application/pdf":"application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes.length,hash(bytes),purpose,actor);
            return db.one("SELECT * FROM files WHERE id=?",id);
        } catch(IOException e) { throw new ApiException(500,"STORAGE_ERROR","文件存储失败，请检查存储空间"); }
        finally { if(temp!=null) try { Files.deleteIfExists(temp); } catch(IOException ignored) { } }
    }
    byte[] read(String id) {
        var file=db.one("SELECT * FROM files WHERE id=?",id);
        // 防御性检查：db.one 目前对空结果已经抛 404，但 read() 的入参可能来自外部拼装，
        // 这里保证"文件 id 为空/记录已清理"永远是 404，而不是在 path() 里踩空指针变成 500。
        require(file!=null,404,"NOT_FOUND","文件不存在或已被清理");
        try { byte[] bytes=Files.readAllBytes(path(file)); require(hash(bytes).equals(file.get("sha256")),409,"FILE_INTEGRITY_ERROR","文件校验失败，请联系管理员恢复备份"); return bytes; }
        catch(IOException e) { throw new ApiException(409,"FILE_MISSING","文件缺失，请联系管理员恢复备份"); }
    }
    Path path(Map<String,Object> f) { Path path=root.resolve(str(f,"storage_key")).normalize(); require(path.startsWith(root),500,"INVALID_STORAGE","文件存储记录无效"); return path; }
    /**
     * 删除没有任何业务引用的文件（数据库行 + 磁盘文件）。
     * 被模板版本、生成任务或签署件引用时不做任何改动并返回 false
     * （复制版本时母版文件会被多个版本共用，必须保护）。
     */
    public boolean purgeIfUnreferenced(String fileId) {
        if(fileId==null||fileId.isBlank()) return false;
        var row=db.find("SELECT * FROM files WHERE id=?",fileId);
        if(row==null) return false;
        if(db.count("SELECT COUNT(*) FROM template_versions WHERE docx_file_id=? OR source_pdf_file_id=? OR original_docx_file_id=?",fileId,fileId,fileId)>0) return false;
        if(db.count("SELECT COUNT(*) FROM generation_jobs WHERE docx_file_id=? OR pdf_file_id=?",fileId,fileId)>0) return false;
        if(db.count("SELECT COUNT(*) FROM signed_attachments WHERE file_id=?",fileId)>0) return false;
        db.update("DELETE FROM files WHERE id=?",fileId);
        try { Files.deleteIfExists(path(row)); } catch(Exception ignored) { }
        return true;
    }
    /**
     * 清理两类垃圾：1) 事务回滚/进程中断后遗留、没有任何数据库引用的文件；
     * 2) 崩溃留下的 write-*.tmp / scan-* 临时文件。只处理超过保留时长的对象，避免误删正在写入的文件。
     */
    int purgeOrphans(int retentionHours) {
        Instant cutoff=Instant.now().minusSeconds(retentionHours*3600L);
        int removed=0;
        var rows=db.list("SELECT f.* FROM files f WHERE f.created_at<? "+
                "AND NOT EXISTS(SELECT 1 FROM template_versions v WHERE v.docx_file_id=f.id OR v.source_pdf_file_id=f.id OR v.original_docx_file_id=f.id) "+
                "AND NOT EXISTS(SELECT 1 FROM generation_jobs j WHERE j.docx_file_id=f.id OR j.pdf_file_id=f.id) "+
                "AND NOT EXISTS(SELECT 1 FROM signed_attachments a WHERE a.file_id=f.id)",at(cutoff));
        for(var row:rows) {
            try { Files.deleteIfExists(path(row)); } catch(IOException ignored) { }
            db.update("DELETE FROM files WHERE id=?",row.get("id"));
            removed++;
        }
        try(var listing=Files.list(root)) {
            for(Path p:listing.toList()) {
                String name=p.getFileName().toString();
                if(!(name.startsWith("write-")||name.startsWith("scan-"))) continue;
                try { if(Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) { Files.deleteIfExists(p); removed++; } } catch(IOException ignored) { }
            }
        } catch(IOException ignored) { }
        return removed;
    }
}
