package cn.qiheng.contracthub;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

final class RequestContext {
    static String requestId() {
        var attrs=RequestContextHolder.getRequestAttributes();
        if(attrs instanceof ServletRequestAttributes a && a.getRequest().getAttribute("request_id") instanceof String s) return s;
        return Db.id();
    }
}
