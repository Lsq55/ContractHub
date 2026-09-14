package cn.qiheng.contracthub;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;
import static cn.qiheng.contracthub.ApiException.require;

@Component
public class SecurityFilter extends OncePerRequestFilter {
    final AuthService auth;
    SecurityFilter(AuthService auth) { this.auth=auth; }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String requestId=Db.id(); req.setAttribute("request_id",requestId);
        res.setHeader("X-Request-ID",requestId); res.setHeader("Cache-Control","private, no-store");
        res.setHeader("X-Content-Type-Options","nosniff"); res.setHeader("X-Frame-Options","SAMEORIGIN"); res.setHeader("Referrer-Policy","same-origin");
        res.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; frame-src 'self' blob:; object-src 'self' blob:; base-uri 'self'; form-action 'self'; frame-ancestors 'self'");
        try {
            String path=req.getRequestURI();
            if(path.startsWith("/api/v1/")&&!Set.of("/api/v1/health","/api/v1/auth/csrf","/api/v1/auth/login").contains(path)) {
                var actor=auth.authenticate(req); req.setAttribute("actor",actor);
                if(Boolean.TRUE.equals(actor.get("must_change_password"))) require(Set.of("/api/v1/auth/me","/api/v1/auth/change-password","/api/v1/auth/logout").contains(path),403,"PASSWORD_CHANGE_REQUIRED","首次登录请先修改密码");
                if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) require(Objects.equals(actor.get("csrf_token"),req.getHeader("X-CSRF-Token")),403,"CSRF_INVALID","请求验证已过期，请刷新后重试");
            }
            if(path.equals("/api/v1/auth/login")) require(!AuthService.cookie(req,"QH_CSRF").isEmpty() && AuthService.cookie(req,"QH_CSRF").equals(req.getHeader("X-CSRF-Token")),403,"CSRF_INVALID","请刷新登录页面后重试");
            chain.doFilter(req,res);
        } catch(ApiException ex) {
            res.setStatus(ex.status); res.setContentType("application/json;charset=UTF-8");
            auth.db.json.writeValue(res.getOutputStream(),Map.of("code",ex.code,"message",ex.getMessage(),"request_id",requestId,"details",ex.details));
        }
    }
}
