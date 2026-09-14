package cn.qiheng.contracthub;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.Map;

@RestControllerAdvice
public class ExceptionAdvice {
    @ExceptionHandler(ApiException.class) ResponseEntity<?> business(ApiException e) { return response(e.status,e.code,e.getMessage(),e.details); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> large(Exception e) { return response(413,"FILE_TOO_LARGE","文件超过上传大小限制",Map.of()); }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> duplicate(Exception e) { return response(409,"DATA_CONFLICT","编号已存在或数据仍被其他记录引用",Map.of()); }
    /** 静态资源不存在是正常的 404，不能记成服务端错误。 */
    @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<?> notFound(NoResourceFoundException e) { return response(404,"NOT_FOUND","资源不存在",Map.of()); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class) ResponseEntity<?> method(Exception e) { return response(405,"METHOD_NOT_ALLOWED","请求方法不被支持",Map.of()); }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,IllegalArgumentException.class}) ResponseEntity<?> bad(Exception e) { return response(422,"INVALID_INPUT","请求内容或参数格式不正确",Map.of()); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Request {} failed: {}",RequestContext.requestId(),e.getClass().getSimpleName());
        return response(500,"INTERNAL_ERROR","操作失败，请联系管理员并提供请求编号",Map.of());
    }
    ResponseEntity<?> response(int status,String code,String msg,Map<String,Object> details) { return ResponseEntity.status(status).body(Map.of("code",code,"message",msg,"request_id",RequestContext.requestId(),"details",details)); }
}
