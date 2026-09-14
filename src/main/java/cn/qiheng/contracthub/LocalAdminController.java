package cn.qiheng.contracthub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** First-account creation is deliberately local only; no anonymous HTTP bootstrap endpoint exists. */
@Component
class LocalAdminController implements ApplicationRunner {
    final AuthService auth; LocalAdminController(AuthService auth) { this.auth=auth; }
    @Override public void run(ApplicationArguments args) {
        if(args.containsOption("init-admin")) {
            String username=args.getOptionValues("username")==null?null:args.getOptionValues("username").getFirst();
            String name=args.getOptionValues("display-name")==null?null:args.getOptionValues("display-name").getFirst();
            String password=args.getOptionValues("password")==null?null:args.getOptionValues("password").getFirst();
            if(username==null||name==null||password==null) throw new IllegalArgumentException("初始化管理员需要 --init-admin --username=... --display-name=... --password=...");
            auth.bootstrap(username,name,password,false); System.out.println("契衡合同管理系统：管理员初始化完成");
        }
        if(args.containsOption("recover-admin")) {
            String username=args.getOptionValues("username")==null?null:args.getOptionValues("username").getFirst(); String name=args.getOptionValues("display-name")==null?null:args.getOptionValues("display-name").getFirst(); String password=args.getOptionValues("password")==null?null:args.getOptionValues("password").getFirst();
            if(username==null||name==null||password==null) throw new IllegalArgumentException("恢复管理员需要 --recover-admin --username=... --display-name=... --password=...");
            auth.bootstrap(username,name,password,true); System.out.println("契衡合同管理系统：管理员恢复完成");
        }
    }
}
