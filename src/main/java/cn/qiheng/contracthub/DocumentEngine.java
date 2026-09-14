package cn.qiheng.contracthub;

import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import static cn.qiheng.contracthub.ApiException.require;
import static cn.qiheng.contracthub.Db.*;

/** Only literal scalar placeholders are accepted. No executable template language is used. */
@Component
public class DocumentEngine {
    static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final Pattern TOKEN=Pattern.compile("\\{\\{([a-z][a-z0-9_]{0,63})}}"), PART=Pattern.compile("word/(document|header[0-9]+|footer[0-9]+)\\.xml");
    Map<String,byte[]> unzip(byte[] bytes) {
        require(bytes.length>4&&bytes[0]=='P'&&bytes[1]=='K',415,"INVALID_DOCX","文件不是有效 DOCX");
        var parts=new LinkedHashMap<String,byte[]>(); long total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while((e=zip.getNextEntry())!=null) {
                String name=e.getName();
                require(!name.contains("..")&&!name.startsWith("/")&&!name.contains("\\")&&parts.size()<1500,422,"UNSAFE_DOCX","DOCX 包结构或文件数量不安全");
                if(e.isDirectory()) continue;
                require(!parts.containsKey(name),422,"UNSAFE_DOCX","DOCX 包含重复文件");
                var out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int read;
                while((read=zip.read(buffer))!=-1) { total+=read; require(total<=100L*1024*1024&&out.size()+read<=30L*1024*1024,422,"ZIP_LIMIT","DOCX 解压体积超过限制"); out.write(buffer,0,read); }
                require(!(name.toLowerCase(Locale.ROOT).contains("vba")||name.startsWith("word/embeddings/")),422,"UNSAFE_DOCX","不支持宏、嵌入对象或自定义 XML");
                byte[] content=out.toByteArray(); parts.put(name,content);
                if(name.endsWith(".rels")) {
                    var dom=parse(content); var rels=dom.getElementsByTagNameNS("*","Relationship");
                    for(int i=0;i<rels.getLength();i++) { var rel=(Element)rels.item(i); require(!rel.getAttribute("TargetMode").equalsIgnoreCase("External"),422,"EXTERNAL_LINK","模板含有外部关系，请删除外链后上传"); }
                }
            }
        } catch(ApiException e) { throw e; } catch(Exception e) { throw new ApiException(422,"INVALID_DOCX","DOCX 文件已损坏或加密"); }
        require(parts.containsKey("word/document.xml")&&parts.containsKey("[Content_Types].xml"),422,"INVALID_DOCX","DOCX 缺少正文或类型声明");
        String types=new String(parts.get("[Content_Types].xml"),StandardCharsets.UTF_8);
        require(!types.contains("macroEnabled")&&!types.contains("vbaProject"),422,"UNSAFE_DOCX","禁止宏模板");
        return parts;
    }
    Document parse(byte[] bytes) {
        try {
            var f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false); f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch(Exception e) { throw new ApiException(422,"INVALID_XML","模板包含不安全或无效的 XML"); }
    }
    List<Element> nodes(Element parent,String local) {
        var result=new ArrayList<Element>(); var list=parent.getElementsByTagNameNS(W,local);
        for(int i=0;i<list.getLength();i++) result.add((Element)list.item(i)); return result;
    }
    String text(Element p) { var s=new StringBuilder(); nodes(p,"t").forEach(t->s.append(t.getTextContent())); return s.toString(); }
    public Map<String,Object> scan(byte[] bytes) {
        var found=new LinkedHashMap<String,Object>(); var parts=unzip(bytes);
        for(var entry:parts.entrySet()) {
            if(!entry.getKey().endsWith(".xml")) continue;
            var dom=parse(entry.getValue()); Element root=dom.getDocumentElement();
            if(entry.getKey().startsWith("word/")) {
                for(String tag:List.of("ins","del","moveFrom","moveTo","altChunk","object")) require(nodes(root,tag).isEmpty(),422,"UNSUPPORTED_STRUCTURE","模板含修订或不支持的结构："+tag);
                for(String tag:List.of("txbxContent","instrText","fldSimple","sdt")) for(Element element:nodes(root,tag)) require(!element.getTextContent().contains("{{"),422,"UNSUPPORTED_PLACEHOLDER","占位符位于不支持的文本框、域或内容控件内");
            }
            if(!PART.matcher(entry.getKey()).matches()) {
                require(!root.getTextContent().contains("{{"),422,"UNSUPPORTED_PLACEHOLDER","占位符只能位于正文、普通表格、页眉或页脚"); continue;
            }
            for(Element p:nodes(root,"p")) {
                String s=text(p);
                String remainder=TOKEN.matcher(s).replaceAll("");
                require(!remainder.contains("{{")&&!remainder.contains("}}")&&!remainder.contains("{%")&&!remainder.contains("{#"),422,"UNKNOWN_TOKEN","存在未知表达式或跨段占位符："+s.substring(0,Math.min(100,s.length())));
                Matcher m=TOKEN.matcher(s);
                while(m.find()) {
                    for(Node ancestor=p.getParentNode();ancestor instanceof Element el;ancestor=ancestor.getParentNode()) {
                        if("tc".equals(el.getLocalName())) require(nodes(el,"vMerge").isEmpty(),422,"COMPLEX_TABLE","纵向合并单元格内的占位符需移入普通单元格");
                    }
                    assertCompatible(p,m.start(),m.end());
                    @SuppressWarnings("unchecked") var occurrences=(List<Map<String,Object>>)found.computeIfAbsent(m.group(1),k->new ArrayList<>());
                    occurrences.add(Map.of("part",entry.getKey(),"context",s.substring(Math.max(0,m.start()-30),Math.min(s.length(),m.end()+40))));
                }
            }
        }
        return found;
    }
    /** Word 会为不同编辑会话写入不同的 w:rsid*，这些属性不代表可见样式，比较时必须剔除。 */
    private static final Pattern IGNORED_RUN_PROPS=Pattern.compile("\\s+w:(rsidR|rsidRPr|rsidDel|rsidTr|proofErr)=\"[^\"]*\"");
    String runStyle(Element run) {
        var props=run.getElementsByTagNameNS(W,"rPr");
        if(props.getLength()==0) return "";
        String xml=new String(serialize(props.item(0)),StandardCharsets.UTF_8);
        // 去掉 rsid/校对标记后再比较，避免把视觉一致但保存痕迹不同的 run 判成样式冲突。
        return IGNORED_RUN_PROPS.matcher(xml).replaceAll("");
    }
    void assertCompatible(Element p,int start,int end) {
        int offset=0; String style=null;
        for(Element t:nodes(p,"t")) {
            int length=t.getTextContent().length();
            if(offset<end&&offset+length>start) {
                Element run=(Element)t.getParentNode(); String current=runStyle(run);
                if(style==null) style=current;
                require(style.equals(current),422,"SPLIT_RUN_STYLE","占位符跨越不同样式，请在 Word 中重新输入完整占位符");
            } offset+=length;
        }
    }
    public Map<String,Object> validate(byte[] bytes,Map<String,Object> schema) {
        var found=scan(bytes); var bound=new HashSet<String>();
        for(var f:fields(schema)) if(!f.containsKey("binding")||f.get("binding")!=null) bound.add(str(f,"key"));
        var missing=new HashSet<>(found.keySet()); missing.removeAll(bound);
        var unused=new HashSet<>(bound); unused.removeAll(found.keySet());
        require(missing.isEmpty()&&unused.isEmpty(),422,"BINDING_MISMATCH","字段绑定不匹配；未配置："+missing+"；母版中不存在："+unused+"（母版是字段来源，可在「字段配置」里点【按母版同步字段】自动对齐；重新上传母版也会自动对齐）");
        return found;
    }
    /** 把一个段落里 [start,end) 范围的文字替换成 value（跨 run 时写进第一个 run，其余清空）。 */
    void replaceRange(List<Element> texts,int start,int end,String value) {
        int offset=0; boolean inserted=false;
        for(Element t:texts) {
            String current=t.getTextContent(); int length=current.length();
            if(offset<end&&offset+length>start) {
                int a=Math.max(0,start-offset),b=Math.min(length,end-offset);
                t.setTextContent(current.substring(0,a)+(inserted?"":value)+current.substring(b));
                t.setAttributeNS(XMLConstants.XML_NS_URI,"xml:space","preserve"); inserted=true;
            } offset+=length;
        }
    }
    byte[] zip(Map<String,byte[]> parts) {
        try(var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out)) { for(var e:parts.entrySet()) { zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue()); zip.closeEntry(); } zip.finish(); return out.toByteArray(); }
        catch(IOException e) { throw new IllegalStateException(e); }
    }
    public byte[] render(byte[] bytes,Map<String,Object> schema,Map<String,String> values) {
        validate(bytes,schema); var parts=unzip(bytes);
        for(var entry:parts.entrySet()) if(PART.matcher(entry.getKey()).matches()) {
            var doc=parse(entry.getValue());
            for(Element p:nodes(doc.getDocumentElement(),"p")) {
                List<Element> texts=nodes(p,"t"); String original=text(p); Matcher matcher=TOKEN.matcher(original);
                var matches=new ArrayList<int[]>(); var keys=new ArrayList<String>();
                while(matcher.find()) { matches.add(new int[]{matcher.start(),matcher.end()}); keys.add(matcher.group(1)); }
                // Work backwards using original offsets; replacement values are never reparsed as tokens.
                for(int k=matches.size()-1;k>=0;k--) replaceRange(texts,matches.get(k)[0],matches.get(k)[1],values.getOrDefault(keys.get(k),""));
                for(Element t:texts) if(t.getTextContent().contains("\n")) {
                    String[] lines=t.getTextContent().replace("\r","").split("\n",-1); t.setTextContent(lines[0]); Node reference=t.getNextSibling(),run=t.getParentNode();
                    for(int i=1;i<lines.length;i++) { run.insertBefore(doc.createElementNS(W,"w:br"),reference); var next=doc.createElementNS(W,"w:t"); next.setAttributeNS(XMLConstants.XML_NS_URI,"xml:space","preserve"); next.setTextContent(lines[i]); run.insertBefore(next,reference); }
                }
            }
            entry.setValue(serialize(doc));
        }
        return zip(parts);
    }
    /** 连续下划线空白：半角 _ 或全角 ＿ 连续 3 个以上。 */
    static final Pattern BLANK=Pattern.compile("[_＿]{3,}");
    /** run 的字体颜色（w:rPr/w:color/@w:val，大写无 #），没有则返回空串。 */
    String runColor(Element run) {
        var props=run.getElementsByTagNameNS(W,"rPr"); if(props.getLength()==0) return "";
        var colors=((Element)props.item(0)).getElementsByTagNameNS(W,"color"); if(colors.getLength()==0) return "";
        return ((Element)colors.item(0)).getAttributeNS(W,"val").toUpperCase(Locale.ROOT);
    }
    /** 去掉 run 的字体颜色与高亮：占位符本身不该带标记色，否则生成出来还是彩色。 */
    void clearColor(Element run) {
        var props=run.getElementsByTagNameNS(W,"rPr"); if(props.getLength()==0) return;
        var rpr=(Element)props.item(0);
        for(String tag:List.of("color","highlight")) {
            var list=rpr.getElementsByTagNameNS(W,tag);
            for(int i=list.getLength()-1;i>=0;i--) rpr.removeChild(list.item(i));
        }
    }
    /** 段落里"连续同色 run"组成的标记段：{start,end,第一个 run,文本}（按出现顺序，相邻同色会合并成一段）。 */
    List<Object[]> colorGroups(Element p,String hex) {
        var out=new ArrayList<Object[]>();
        int offset=0,groupStart=-1,groupEnd=-1; Element firstRun=null; var sb=new StringBuilder();
        for(Element t:nodes(p,"t")) {
            String cur=t.getTextContent(); Element run=(Element)t.getParentNode();
            boolean hit=!hex.isEmpty()&&hex.equals(runColor(run));
            if(hit) { if(groupStart<0) { groupStart=offset; firstRun=run; } groupEnd=offset+cur.length(); sb.append(cur); }
            else if(groupStart>=0) { out.add(new Object[]{groupStart,groupEnd,firstRun,sb.toString()}); groupStart=-1; firstRun=null; sb.setLength(0); }
            offset+=cur.length();
        }
        if(groupStart>=0) out.add(new Object[]{groupStart,groupEnd,firstRun,sb.toString()});
        return out;
    }
    /**
     * 把"用指定颜色标记的文字"改写成 {{auto_N}}：相邻同色 run 合并成一个字段，
     * 占位符写入这段的第一个 run 并去掉标记色。返回规范化后的 DOCX、按顺序的默认中文名与识别数量。
     */
    public Map<String,Object> normalizeColor(byte[] bytes,String hex,int startIndex) {
        var parts=unzip(bytes);
        var docs=new LinkedHashMap<String,Document>(); var todo=new LinkedHashMap<String,List<Object[]>>(); var contexts=new ArrayList<String>();
        int n=startIndex;
        for(var entry:parts.entrySet()) {
            if(!PART.matcher(entry.getKey()).matches()) continue;
            var doc=parse(entry.getValue()); docs.put(entry.getKey(),doc); var list=new ArrayList<Object[]>();
            for(Element p:nodes(doc.getDocumentElement(),"p")) {
                String s=text(p);
                for(var g:colorGroups(p,hex)) {
                    int st=(int)g[0], en=(int)g[1]; String txt=(String)g[3];
                    String ctx=blankContext(s,st,en);
                    if("待填项".equals(ctx)&&!txt.isBlank()) ctx=txt.trim().length()<=12?txt.trim():ctx;
                    contexts.add(ctx);
                    list.add(new Object[]{p,st,en,g[2],"{{auto_"+(++n)+"}}"});
                }
            }
            todo.put(entry.getKey(),list);
        }
        for(var e:todo.entrySet()) {
            var byPara=new LinkedHashMap<Element,List<Object[]>>();
            for(var o:e.getValue()) byPara.computeIfAbsent((Element)o[0],k->new ArrayList<>()).add(o);
            for(var ent:byPara.entrySet()) {
                var items=ent.getValue(); items.sort((a,b)->Integer.compare((int)b[1],(int)a[1]));
                for(var o:items) {
                    int st=(int)o[1], en=(int)o[2]; Element run=(Element)o[3]; String ph=(String)o[4];
                    if(st==en) { run.setTextContent(ph); run.setAttributeNS(XMLConstants.XML_NS_URI,"xml:space","preserve"); }
                    else replaceRange(nodes(ent.getKey(),"t"),st,en,ph);
                    clearColor(run);
                }
                // 合并后被清空的那些同色 run 也要去掉标记色，免得母版里残留一片"红色空白"
                for(Element r:nodes(ent.getKey(),"r")) if(text(r).isEmpty()&&hex.equals(runColor(r))) clearColor(r);
            }
        }
        for(var e:parts.entrySet()) if(docs.containsKey(e.getKey())) e.setValue(serialize(docs.get(e.getKey())));
        return Map.of("docx",zip(parts),"contexts",contexts,"count",contexts.size());
    }
    /** 取空白前面的文字当"默认中文名"（去掉下划线、结尾冒号与空白），取不到就用后面的文字。 */
    String blankContext(String s,int start,int end) {
        String before=s.substring(0,start).replaceAll("[_＿]+"," ").replaceAll("\\s+"," ").trim().replaceAll("[：:、,，\\s]+$","");
        if(!before.isEmpty()) { return before.length()>12?before.substring(before.length()-12):before; }
        String after=s.substring(end).replaceAll("[_＿]+"," ").replaceAll("\\s+"," ").trim();
        return after.isEmpty()?"待填项":(after.length()>12?after.substring(0,12):after);
    }
    /** 扫描母版里的连续下划线空白，按文档顺序返回（用于给自动字段起中文名）。 */
    public List<String> scanBlanks(byte[] bytes) {
        var parts=unzip(bytes); var out=new ArrayList<String>();
        for(var entry:parts.entrySet()) {
            if(!PART.matcher(entry.getKey()).matches()) continue;
            var doc=parse(entry.getValue());
            for(Element p:nodes(doc.getDocumentElement(),"p")) {
                String s=text(p); var m=BLANK.matcher(s);
                while(m.find()) out.add(blankContext(s,m.start(),m.end()));
            }
        }
        return out;
    }
    /**
     * 把连续下划线空白改写成 {{auto_1}}…{{auto_N}}，返回规范化后的 DOCX 与按顺序的默认中文名。
     * 占位符只写进原下划线所在的第一个 run，因此不会出现"跨 run 样式不一致"的问题。
     */
    public Map<String,Object> normalizeBlanks(byte[] bytes) { return normalizeBlanks(bytes,0); }
    public Map<String,Object> normalizeBlanks(byte[] bytes,int startIndex) {
        var parts=unzip(bytes);
        var docs=new LinkedHashMap<String,Document>(); var todo=new LinkedHashMap<String,List<Object[]>>(); var contexts=new ArrayList<String>();
        int n=startIndex;
        for(var entry:parts.entrySet()) {
            if(!PART.matcher(entry.getKey()).matches()) continue;
            var doc=parse(entry.getValue()); docs.put(entry.getKey(),doc); var list=new ArrayList<Object[]>();
            for(Element p:nodes(doc.getDocumentElement(),"p")) {
                String s=text(p); var m=BLANK.matcher(s);
                while(m.find()) { contexts.add(blankContext(s,m.start(),m.end())); list.add(new Object[]{p,m.start(),m.end(),"{{auto_"+(++n)+"}}"}); }
            }
            todo.put(entry.getKey(),list);
        }
        for(var e:todo.entrySet()) {
            var byPara=new LinkedHashMap<Element,List<Object[]>>();
            for(var o:e.getValue()) byPara.computeIfAbsent((Element)o[0],k->new ArrayList<>()).add(o);
            for(var ent:byPara.entrySet()) {
                var items=ent.getValue(); items.sort((a,b)->Integer.compare((int)b[1],(int)a[1]));   // 同一段落内倒序替换，保证偏移有效
                for(var o:items) replaceRange(nodes(ent.getKey(),"t"),(int)o[1],(int)o[2],(String)o[3]);
            }
        }
        for(var e:parts.entrySet()) if(docs.containsKey(e.getKey())) e.setValue(serialize(docs.get(e.getKey())));
        return Map.of("docx",zip(parts),"contexts",contexts,"count",contexts.size());
    }
    byte[] serialize(Node node) {
        try { var factory=TransformerFactory.newInstance(); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,""); var transformer=factory.newTransformer(); transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8"); var out=new ByteArrayOutputStream(); transformer.transform(new DOMSource(node),new StreamResult(out)); return out.toByteArray(); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}

