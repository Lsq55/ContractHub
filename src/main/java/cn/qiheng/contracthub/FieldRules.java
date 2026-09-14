package cn.qiheng.contracthub;

import org.springframework.stereotype.Component;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import static cn.qiheng.contracthub.Db.*;
import static cn.qiheng.contracthub.ApiException.require;

@Component
public class FieldRules {
    private static final Set<String> TYPES=Set.of("text","textarea","date","money","integer","select","boolean","computed");
    public void schema(Map<String,Object> schema) {
        require(schema.get("fields") instanceof List<?> && !fields(schema).isEmpty()&&fields(schema).size()<=150,422,"INVALID_SCHEMA","字段配置必须包含 1～150 个字段");
        var keys=new HashSet<String>(); var groups=new HashSet<String>();
        require(schema.getOrDefault("groups",List.of()) instanceof List<?>,422,"INVALID_SCHEMA","分组格式无效");
        for(Object g:(List<?>)schema.getOrDefault("groups",List.of())) {
            require(g instanceof Map<?,?>,422,"INVALID_SCHEMA","分组格式无效");
            var m=(Map<?,?>)g; require(m.get("key") instanceof String&&m.get("label") instanceof String&&groups.add((String)m.get("key")),422,"INVALID_SCHEMA","分组标识必须唯一且具有名称");
        }
        for(var f:fields(schema)) {
            String key=str(f,"key"),type=str(f,"type");
            require(key.matches("[a-z][a-z0-9_]{0,63}")&&keys.add(key),422,"INVALID_SCHEMA","字段 key 无效或重复："+key);
            require(TYPES.contains(type),422,"INVALID_SCHEMA","不支持的字段类型："+type);
            required(f,"label",100);
            require(!f.containsKey("binding")||f.get("binding")==null||key.equals(f.get("binding")),422,"INVALID_SCHEMA","binding 必须为同名 key 或 null");
            require(!f.containsKey("group")||groups.contains(str(f,"group")),422,"INVALID_SCHEMA","字段引用了不存在的分组");
            require(!f.containsKey("required")||f.get("required") instanceof Boolean,422,"INVALID_SCHEMA","required 必须为布尔值");
            if(type.equals("computed")) require(str(f,"calculator").equals("rmb_upper"),422,"INVALID_SCHEMA","仅支持内置人民币大写计算器");
            if(type.equals("select")) {
                require(f.get("options") instanceof List<?> options&&!options.isEmpty()&&options.size()<=100&&options.stream().allMatch(x->x instanceof String),422,"INVALID_SCHEMA","选择字段需要字符串选项列表");
            }
            if(f.containsKey("max_length")) require(num(f,"max_length")>0&&num(f,"max_length")<=10000,422,"INVALID_SCHEMA","字段长度限制应为 1～10000");
            if(f.containsKey("scale")) require(num(f,"scale")==2,422,"INVALID_SCHEMA","第一阶段金额使用两位小数");
            if(f.containsKey("min")&&f.containsKey("max")) require(new BigDecimal(str(f,"min")).compareTo(new BigDecimal(str(f,"max")))<=0,422,"INVALID_SCHEMA","最小值不能大于最大值");
            if(f.containsKey("default")&&f.get("default")!=null&&!type.equals("computed")) normalize(f,f.get("default"));
        }
        for(var f:fields(schema)) {
            if(str(f,"type").equals("computed")) require(fields(schema).stream().anyMatch(s->str(s,"key").equals(str(f,"source"))&&str(s,"type").equals("money")),422,"INVALID_SCHEMA","人民币大写必须依赖一个金额字段");
            if(f.containsKey("after")) require(str(f,"type").equals("date")&&fields(schema).stream().anyMatch(s->str(s,"key").equals(str(f,"after"))&&str(s,"type").equals("date")&&!s.equals(f)),422,"INVALID_SCHEMA","日期比较必须引用其他日期字段");
        }
    }
    /** 必填提示：有中文名就用中文名；名字与 key 相同（自动扫描的默认值）时不重复输出 key。 */
    static String requiredMessage(Map<String,Object> f) {
        String label=str(f,"label"), key=str(f,"key");
        if(label.isBlank()||label.equals(key)) return "必填项未填写";
        return label+"为必填项";
    }
    public Map<String,Object> validate(Map<String,Object> schema,Map<String,Object> data,boolean strict) {
        var out=new LinkedHashMap<String,Object>(); var errors=new LinkedHashMap<String,Object>();
        var known=new HashSet<String>(); fields(schema).forEach(f->known.add(str(f,"key")));
        for(String key:data.keySet()) if(!known.contains(key)) errors.put(key,"未知字段");
        for(var f:fields(schema)) {
            String key=str(f,"key"); Object raw=data.get(key);
            // 计算字段始终由后端按 source 重算；客户端回传历史值（表单回填）时忽略而不是报错，
            // 否则草稿在第一次保存/生成写入大写金额后就再也保存不了。
            if(str(f,"type").equals("computed")) continue;
            if(raw==null||raw.toString().isBlank()) { if(strict&&Boolean.TRUE.equals(f.get("required"))) errors.put(key,requiredMessage(f)); out.put(key,""); continue; }
            try { out.put(key,normalize(f,raw)); } catch(Exception e) { errors.put(key,e instanceof ApiException?e.getMessage():str(f,"label")+"格式不正确"); }
        }
        for(var f:fields(schema)) {
            String key=str(f,"key");
            if(str(f,"type").equals("computed")) { String source=Objects.toString(out.get(str(f,"source")),""); out.put(key,source.isBlank()?"":rmb(new BigDecimal(source))); }
            if(str(f,"type").equals("date")&&!str(f,"after").isBlank()&&!str(out,key).isBlank()&&!str(out,str(f,"after")).isBlank()) {
                if(str(out,key).compareTo(str(out,str(f,"after")))<0) errors.put(key,str(f,"label")+"不得早于起始日期");
            }
        }
        if(!errors.isEmpty()) throw new ApiException(422,"FIELD_VALIDATION_FAILED","请检查填写内容",errors);
        return out;
    }
    public Map<String,Object> editable(Map<String,Object> schema,Map<String,Object> values) {
        var data=new LinkedHashMap<String,Object>(); for(var f:fields(schema)) if(!str(f,"type").equals("computed")&&values.containsKey(str(f,"key"))) data.put(str(f,"key"),values.get(str(f,"key"))); return data;
    }
    Object normalize(Map<String,Object> f,Object raw) {
        String value=raw.toString().trim(), type=str(f,"type"),label=str(f,"label");
        require(raw instanceof String||raw instanceof Boolean||raw instanceof Number,422,"INVALID_VALUE",label+"必须为单值");
        require(value.chars().noneMatch(c->(c<32&&c!='\n'&&c!='\r'&&c!='\t')||c==0xfffe||c==0xffff),422,"INVALID_VALUE",label+"包含不支持的控制字符");
        switch(type) {
            case "text", "textarea" -> {
                int max=f.containsKey("max_length")?num(f,"max_length"):(type.equals("text")?200:3000);
                require(value.length()<=max,422,"INVALID_VALUE",label+"最多 "+max+" 个字符");
                require(type.equals("textarea")||!value.contains("\n"),422,"INVALID_VALUE",label+"不能包含换行");
                require(value.lines().count()<=50,422,"INVALID_VALUE",label+"最多 50 行"); return value;
            }
            case "date" -> { return LocalDate.parse(value).toString(); }
            case "money", "integer" -> {
                require(!type.equals("money")||raw instanceof String,422,"INVALID_VALUE",label+"必须以十进制字符串传输");
                require(value.matches(type.equals("money")?"[0-9]+(\\.[0-9]{1,2})?":"-?[0-9]+"),422,"INVALID_VALUE",label+"数值格式不正确");
                var n=new BigDecimal(value); var min=new BigDecimal(Objects.toString(f.get("min"),"0")); var max=new BigDecimal(Objects.toString(f.get("max"),"999999999.99"));
                require(n.compareTo(min)>=0&&n.compareTo(max)<=0,422,"INVALID_VALUE",label+"须在 "+min+"～"+max+" 之间");
                return n.setScale(type.equals("money")?2:0,RoundingMode.UNNECESSARY).toPlainString();
            }
            case "select" -> { require(((List<?>)f.get("options")).contains(value),422,"INVALID_VALUE",label+"不在可选范围内"); return value; }
            case "boolean" -> {
                // 表单控件可能提交字符串 true/false，这里归一为真正的布尔值，避免"字段永远填不了"。
                if(raw instanceof Boolean b) return b;
                require(raw instanceof String,422,"INVALID_VALUE",label+"必须明确选择是或否");
                String text=value.toLowerCase(Locale.ROOT);
                require(Set.of("true","false","是","否").contains(text),422,"INVALID_VALUE",label+"必须明确选择是或否");
                return text.equals("true")||text.equals("是");
            }
            default -> throw new ApiException(422,"INVALID_VALUE","不支持此字段输入");
        }
    }
    public Map<String,String> renderValues(Map<String,Object> schema,Map<String,Object> data) {
        var out=new LinkedHashMap<String,String>();
        for(var f:fields(schema)) {
            String key=str(f,"key"),v=str(data,key);
            if(str(f,"type").equals("boolean")&&!v.isBlank()) v=Objects.toString(f.get(v.equals("true")?"true_label":"false_label"),v.equals("true")?"是":"否");
            if(str(f,"type").equals("date")&&!v.isBlank()&&"chinese".equals(f.get("format"))) { var d=LocalDate.parse(v); v=d.getYear()+"年"+d.getMonthValue()+"月"+d.getDayOfMonth()+"日"; }
            out.put(key,v);
        } return out;
    }
    public static String rmb(BigDecimal value) {
        require(value.signum()>=0&&value.compareTo(new BigDecimal("999999999.99"))<=0,422,"INVALID_MONEY","金额超出人民币大写支持范围");
        long cents=value.setScale(2,RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        long yuan=cents/100; int jiao=(int)(cents/10%10),fen=(int)(cents%10);
        String[] digits={"零","壹","贰","叁","肆","伍","陆","柒","捌","玖"};
        String out=chineseInteger(yuan)+"元";
        if(jiao==0&&fen==0) return out+"整";
        if(jiao>0) out+=digits[jiao]+"角"; else if(yuan>0) out+="零";
        if(fen>0) out+=digits[fen]+"分"; return out;
    }
    private static String chineseInteger(long n) {
        if(n==0) return "零";
        String[] d={"零","壹","贰","叁","肆","伍","陆","柒","捌","玖"},u={"","拾","佰","仟"},g={"","万","亿"};
        String result=""; boolean pending=false;
        for(int group=2;group>=0;group--) {
            long divisor=(long)Math.pow(10000,group);
            int section=(int)(n/divisor%10000);
            if(section==0) { if(!result.isEmpty()) pending=true; }
            else {
                if(!result.isEmpty()&&(pending||section<1000)) result+="零";
                String part=""; boolean zero=false;
                for(int i=0;i<4;i++) { int digit=section%10; section/=10; if(digit==0) { if(!part.isEmpty()) zero=true; } else { part=d[digit]+u[i]+(zero?"零":"")+part; zero=false; } }
                result+=part+g[group]; pending=false;
            }
        }
        return result;
    }
}
