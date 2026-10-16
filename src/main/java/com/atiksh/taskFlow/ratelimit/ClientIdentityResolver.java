
package com.atiksh.taskFlow.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ClientIdentityResolver {

    public String resolveClientId(HttpServletRequest request) {
        var principal = request.getUserPrincipal();

        if (principal != null) {
            return "user:" + principal.getName();
        }

        return "ip:" + request.getRemoteAddr();
    }
}
