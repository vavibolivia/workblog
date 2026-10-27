package com.workblog.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the built React app for client-side routes when the frontend build is bundled into the jar. */
@Controller
public class SpaForwardController {
    @GetMapping(value = {"/login", "/posts", "/posts/**", "/write", "/search", "/history", "/history/**", "/admin", "/admin/**", "/me", "/trash", "/signup", "/help", "/help/**"})
    public String forward() {
        return "forward:/index.html";
    }

    /** 자체 호스팅 draw.io: /drawio 와 /drawio/ 를 폴더의 index.html 로(쿼리 문자열 유지). 파일은 static-extra/drawio 에서 서비스된다. */
    @GetMapping(value = {"/drawio", "/drawio/"})
    public String drawio() {
        return "forward:/drawio/index.html";
    }
}
