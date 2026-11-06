package com.wherelee.helpdesk.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.wherelee.helpdesk.tenant.TenantContext;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/agents")
    public List<AgentView> agents() {
        return users.listAssignable(TenantContext.get().tenantId()).stream()
                .map(user -> new AgentView(
                        user.id(), user.displayName(), user.email(), user.role()))
                .toList();
    }

    public record AgentView(UUID id, String displayName, String email, String role) {
    }
}
