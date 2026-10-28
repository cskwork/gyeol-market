package io.github.cskwork.lunepick.shared.internal.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** React Router paths share the built entry point; missing assets must remain 404. */
@Controller
public class SpaController {
    // ResourceHttpRequestHandler rejects empty paths before consulting resource resolvers.
    @GetMapping(
            value = {"/app", "/app/"},
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public Resource entry() {
        return new ClassPathResource("static/app/index.html");
    }
}
