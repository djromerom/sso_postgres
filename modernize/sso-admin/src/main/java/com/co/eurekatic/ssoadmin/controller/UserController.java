package com.co.eurekatic.ssoadmin.controller;

import com.co.eurekatic.ssoadmin.dto.BindUserRoleRequest;
import com.co.eurekatic.ssoadmin.dto.CreateAccountRequest;
import com.co.eurekatic.ssoadmin.dto.TokenPasswordRequest;
import com.co.eurekatic.ssoadmin.dto.UpdateAccountRequest;
import com.co.eurekatic.ssoadmin.dto.UserResponse;
import com.co.eurekatic.ssoadmin.service.UserAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User CRUD + activation flow + role bindings. Path layout
 * mirrors the legacy {@code com.co.lowcode.sso.controller.UserController}
 * to keep the API surface stable for clients.
 */
@RestController
@RequestMapping
public class UserController {

    private final UserAdminService service;

    public UserController(UserAdminService service) {
        this.service = service;
    }

    @PostMapping("/createAccount")
    public ResponseEntity<UserResponse> createAccount(@Valid @RequestBody CreateAccountRequest req) {
        UserResponse created = service.createAccount(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/updateAccount")
    public UserResponse updateAccount(@Valid @RequestBody UpdateAccountRequest req) {
        return service.updateAccount(req);
    }

    /**
     * Public endpoint (no auth) — the user arrives here from the
     * activation email link. Returns 200 with an empty body on
     * success.
     *
     * <p>POST + JSON body (not GET + query string): the activation
     * email hands the user a token (delivered in the link the user
     * clicks); the SPA renders a password form on the landing
     * page and POSTs the body here. The password never travels in
     * the URL — no leak via access logs, proxy logs, browser
     * history, or the {@code Referer} header.
     */
    @PostMapping("/activateAccount")
    public ResponseEntity<Void> activateAccount(@Valid @RequestBody TokenPasswordRequest req) {
        service.activateAccount(req.token(), req.password());
        return ResponseEntity.ok().build();
    }

    /**
     * Public endpoint (no auth) — the user submits their email
     * to receive a restore link. Always returns 200 even when
     * the email is unknown (no enumeration).
     */
    @GetMapping("/forgotPassword")
    public ResponseEntity<Void> forgotPassword(@RequestParam String email) {
        service.forgotPassword(email);
        return ResponseEntity.ok().build();
    }

    /**
     * Public restore endpoint (paired with the email sent by
     * {@link #forgotPassword}). Mirrors {@link #activateAccount}
     * but for the restore flow. Same POST + body shape as
     * {@link #activateAccount} — uses the shared
     * {@link TokenPasswordRequest} DTO. Password never travels
     * in the URL.
     */
    @PostMapping("/restorePassword")
    public ResponseEntity<Void> restorePassword(@Valid @RequestBody TokenPasswordRequest req) {
        service.restorePassword(req.token(), req.password());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/getUsers")
    public List<UserResponse> getUsers() {
        return service.getUsers();
    }

    /**
     * Returns the role names for the user identified by email.
     * Email is the unique login identifier since the V12
     * migration (the prior {@code username} column is gone).
     * Renamed from the legacy {@code getRolesByUsername} so the
     * URL reflects the actual lookup key.
     */
    @GetMapping("/getRolesByEmail")
    public List<String> getRolesByEmail(@RequestParam String email) {
        return service.getRolesByEmail(email);
    }

    @GetMapping("/user/roles")
    public List<String> getRolesForUser(@RequestParam Long userId) {
        return service.getRolesForUser(userId);
    }

    @PostMapping("/bindUserRole")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bindUserRole(@Valid @RequestBody BindUserRoleRequest req) {
        service.bindUserRole(req.userId(), req.roleId());
    }

    @DeleteMapping("/unbindUserRole")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbindUserRole(@RequestParam Long userId, @RequestParam Long roleId) {
        service.unbindUserRole(userId, roleId);
    }
}
