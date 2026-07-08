package com.co.eurekatic.ssoadmin.service;

import com.co.eurekatic.common.entity.Group;
import com.co.eurekatic.common.entity.Role;
import com.co.eurekatic.common.entity.User;
import com.co.eurekatic.common.repository.GroupRepository;
import com.co.eurekatic.common.repository.RoleRepository;
import com.co.eurekatic.common.repository.UserRepository;
import com.co.eurekatic.ssoadmin.dto.GroupRequest;
import com.co.eurekatic.ssoadmin.dto.GroupResponse;
import com.co.eurekatic.ssoadmin.exception.DuplicateException;
import com.co.eurekatic.ssoadmin.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Group CRUD and user↔group bindings. The legacy
 * {@code com.co.lowcode.sso.service.GroupService} also
 * supported app↔group bindings — those require the
 * {@code app} table which lands in Phase 3, so we defer the
 * {@code bindAppGroup} endpoint to Phase 3 as well.
 */
@Service
public class GroupAdminService {

    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public GroupAdminService(GroupRepository groupRepository,
                             UserRepository userRepository,
                             RoleRepository roleRepository) {
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    /**
     * Idempotent — if a group with the same name already
     * exists, returns the existing one. The legacy did the
     * same (raw SQL with "IF NOT EXISTS"-like semantics).
     */
    @Transactional
    public GroupResponse save(GroupRequest req) {
        Group group = groupRepository.findByName(req.name())
                .orElseGet(() -> {
                    Group g = new Group();
                    g.setName(req.name());
                    return g;
                });
        group.setDescription(req.description());
        return GroupResponse.fromEntity(groupRepository.save(group));
    }

    /**
     * Updates an existing group by id — the admin-ui edit flow
     * uses this instead of {@link #save}, which resolves by
     * name and would otherwise create a duplicate row (or, on a
     * name collision with a different group, silently overwrite
     * it) when used to rename one.
     */
    @Transactional
    public GroupResponse update(GroupRequest req) {
        if (req.id() == null) {
            throw new IllegalArgumentException("id is required for update");
        }
        Group group = groupRepository.findById(req.id())
                .orElseThrow(() -> new NotFoundException("Group", req.id()));
        // Allow keeping the same name; reject a rename onto a different group's name.
        groupRepository.findByName(req.name()).ifPresent(existing -> {
            if (!existing.getId().equals(group.getId())) {
                throw new DuplicateException("Group", req.name());
            }
        });
        group.setName(req.name());
        group.setDescription(req.description());
        return GroupResponse.fromEntity(groupRepository.save(group));
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> getGroups() {
        return groupRepository.findAll().stream()
                .map(GroupResponse::fromEntity)
                .toList();
    }

    /**
     * Binds a user to a group. Idempotent.
     */
    @Transactional
    public void bindUserGroup(Long userId, Long groupId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User", userId));
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group", groupId));
        group.addUser(user);
        groupRepository.save(group);
    }

    /* ====================== bindings: role ====================== */

    @Transactional
    public void bindRole(Long groupId, Long roleId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group", groupId));
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new NotFoundException("Role", roleId));
        group.addRole(role);
        groupRepository.save(group);
    }

    @Transactional
    public void unbindRole(Long groupId, Long roleId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group", groupId));
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new NotFoundException("Role", roleId));
        group.removeRole(role);
        groupRepository.save(group);
    }

    @Transactional(readOnly = true)
    public List<RoleChecked> getRolesForGroupChecked(Long groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group", groupId));
        Set<Long> bound = group.getRoles().stream()
                .map(Role::getId)
                .collect(Collectors.toSet());
        return roleRepository.findAll().stream()
                .map(r -> new RoleChecked(r.getId(), r.getName(), bound.contains(r.getId())))
                .toList();
    }

    public record RoleChecked(Long roleId, String name, boolean checked) {}
}
