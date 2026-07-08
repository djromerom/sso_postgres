package com.co.eurekatic.ssoadmin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /createAccount}. The admin no
 * longer types a password — the user sets their own by clicking
 * the activation link in the email. {@code POST /activateAccount}
 * is the only place where a password first enters the system;
 * it BCrypts it and stamps {@code enabled=true} /
 * {@code active=true}.
 *
 * <p>Mirrors the legacy
 * {@code com.co.lowcode.sso.model.User} DTO minus the password
 * fields, with modern Jakarta Bean Validation annotations.
 */
public record CreateAccountRequest(
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Size(min = 3, max = 80) String username,
        @NotBlank @Email @Size(max = 200) String email,
        /** Roles to grant. Empty list → no roles. */
        List<@NotBlank String> roleNames
) {}
