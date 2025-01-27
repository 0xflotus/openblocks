package com.openblocks.api.usermanagement;

import static com.google.common.collect.Sets.newHashSet;
import static com.openblocks.sdk.exception.BizError.CANNOT_LEAVE_GROUP;
import static com.openblocks.sdk.exception.BizError.CANNOT_REMOVE_MYSELF;
import static com.openblocks.sdk.exception.BizError.INVALID_GROUP_ID;
import static com.openblocks.sdk.exception.BizError.NOT_AUTHORIZED;
import static com.openblocks.sdk.util.ExceptionUtils.deferredError;
import static com.openblocks.sdk.util.ExceptionUtils.ofError;
import static java.util.Collections.emptyList;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.openblocks.api.home.SessionUserService;
import com.openblocks.api.usermanagement.view.CreateGroupRequest;
import com.openblocks.api.usermanagement.view.GroupMemberAggregateView;
import com.openblocks.api.usermanagement.view.GroupMemberView;
import com.openblocks.api.usermanagement.view.GroupView;
import com.openblocks.api.usermanagement.view.UpdateGroupRequest;
import com.openblocks.api.usermanagement.view.UpdateRoleRequest;
import com.openblocks.domain.bizthreshold.BizThresholdChecker;
import com.openblocks.domain.group.model.Group;
import com.openblocks.domain.group.model.GroupMember;
import com.openblocks.domain.group.service.GroupMemberService;
import com.openblocks.domain.group.service.GroupService;
import com.openblocks.domain.organization.model.MemberRole;
import com.openblocks.domain.organization.model.OrgMember;
import com.openblocks.domain.user.model.User;
import com.openblocks.domain.user.service.UserService;
import com.openblocks.sdk.exception.BizError;
import com.openblocks.sdk.util.StreamUtils;

import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

@Service
public class GroupApiService {

    @Autowired
    private SessionUserService sessionUserService;
    @Autowired
    private GroupMemberService groupMemberService;
    @Autowired
    private UserService userService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private BizThresholdChecker bizThresholdChecker;

    public Mono<GroupMemberAggregateView> getGroupMembers(String groupId, int page, int count) {
        Mono<Tuple2<GroupMember, OrgMember>> groupAndOrgMemberInfo = getGroupAndOrgMemberInfo(groupId).cache();

        Mono<MemberRole> visitorRoleMono = groupAndOrgMemberInfo.flatMap(tuple -> {
            GroupMember groupMember = tuple.getT1();
            OrgMember orgMember = tuple.getT2();
            if (groupMember.isAdmin() || orgMember.isAdmin()) {
                return Mono.just(MemberRole.ADMIN);
            }
            if (groupMember.isValid()) {
                return Mono.just(MemberRole.MEMBER);
            }
            return ofError(NOT_AUTHORIZED, "NOT_AUTHORIZED");
        });

        return groupAndOrgMemberInfo
                .filter(this::hasReadPermission)
                .switchIfEmpty(deferredError(NOT_AUTHORIZED, "NOT_AUTHORIZED"))
                .flatMap(groupMember -> groupMemberService.getGroupMembers(groupId, page, count))
                .<List<GroupMemberView>> flatMap(members -> {
                    if (members.isEmpty()) {
                        return Mono.just(emptyList());
                    }

                    List<String> userIds = StreamUtils.collectList(members, GroupMember::getUserId);
                    Mono<Map<String, User>> userMapMono = userService.getByIds(userIds);
                    return userMapMono.map(map ->
                            members.stream()
                                    .map(orgMember -> {
                                        User user = map.get(orgMember.getUserId());
                                        if (user == null) {
                                            return null;
                                        }
                                        return new GroupMemberView(orgMember, user);
                                    })
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toList()));
                })
                .zipWith(visitorRoleMono)
                .map(tuple -> {
                    List<GroupMemberView> t1 = tuple.getT1();
                    return GroupMemberAggregateView.builder()
                            .members(t1)
                            .visitorRole(tuple.getT2().getValue())
                            .build();
                });
    }

    private boolean hasReadPermission(Tuple2<GroupMember, OrgMember> tuple) {
        GroupMember groupMember = tuple.getT1();
        OrgMember orgMember = tuple.getT2();
        return groupMember.isValid() || orgMember.isAdmin();
    }

    private boolean hasManagePermission(Tuple2<GroupMember, OrgMember> tuple) {
        GroupMember groupMember = tuple.getT1();
        OrgMember orgMember = tuple.getT2();
        return groupMember.isAdmin() || orgMember.isAdmin();
    }

    private Mono<Tuple2<GroupMember, OrgMember>> getGroupAndOrgMemberInfo(String groupId) {
        Mono<GroupMember> groupMemberMono = sessionUserService.getVisitorId()
                .flatMap(visitorId -> groupMemberService.getGroupMember(groupId, visitorId))
                .defaultIfEmpty(GroupMember.NOT_EXIST);

        Mono<OrgMember> orgMemberMono = sessionUserService.getVisitorOrgMemberCache()
                .flatMap(orgMember -> groupService.getById(groupId)
                        .filter(group -> group.getOrganizationId().equals(orgMember.getOrgId()))
                        .switchIfEmpty(deferredError(INVALID_GROUP_ID, "INVALID_GROUP_ID"))
                        .thenReturn(orgMember)
                )
                .switchIfEmpty(deferredError(INVALID_GROUP_ID, "INVALID_GROUP_ID"));

        return Mono.zip(groupMemberMono, orgMemberMono);
    }

    public Mono<Boolean> addGroupMember(String groupId, String newUserId, String roleName) {
        return getGroupAndOrgMemberInfo(groupId)
                .filter(this::hasManagePermission)
                .switchIfEmpty(deferredError(BizError.NOT_AUTHORIZED, "NOT_AUTHORIZED"))
                .flatMap(tuple -> {
                    String orgId = tuple.getT2().getOrgId();
                    return groupMemberService.addMember(orgId, groupId, newUserId, MemberRole.fromValue(roleName));
                });
    }

    public Mono<Boolean> updateRoleForMember(String groupId, UpdateRoleRequest updateRoleRequest) {
        return getGroupAndOrgMemberInfo(groupId)
                .filter(this::hasManagePermission)
                .switchIfEmpty(deferredError(BizError.NOT_AUTHORIZED, "NOT_AUTHORIZED"))
                .then(groupMemberService.updateMemberRole(groupId,
                        updateRoleRequest.getUserId(),
                        MemberRole.fromValue(updateRoleRequest.getRole())));
    }

    public Mono<Boolean> leaveGroup(String groupId) {
        return Mono.zip(sessionUserService.getVisitorId(), groupMemberService.getAllGroupAdmin(groupId))
                .flatMap(tuple -> {
                    String visitorId = tuple.getT1();
                    List<GroupMember> groupAdmins = tuple.getT2();
                    if (groupAdmins.size() == 1 && groupAdmins.get(0).getUserId().equals(visitorId)) {
                        return ofError(CANNOT_LEAVE_GROUP, "CANNOT_LEAVE_GROUP");
                    }
                    return groupMemberService.removeMember(groupId, visitorId);
                });
    }

    public Mono<List<GroupView>> getGroups() {

        return sessionUserService.isAnonymousUser()
                .flatMap(isAnonymousUser -> {
                    if (isAnonymousUser) {
                        return Mono.just(emptyList());
                    }

                    return sessionUserService.getVisitorOrgMemberCache()
                            .flatMap(orgMember -> {
                                String orgId = orgMember.getOrgId();
                                if (orgMember.isAdmin()) {
                                    return groupService.getByOrgId(orgId)
                                            .sort()
                                            .flatMapSequential(GroupView::from)
                                            .collectList();
                                }
                                return groupMemberService.getUserAllGroupIds(orgMember.getUserId())
                                        .flatMapMany(ids -> groupService.getByIds(newHashSet(ids)))
                                        .filter(it -> it.getOrganizationId().equals(orgId))
                                        .sort()
                                        .flatMapSequential(GroupView::from)
                                        .collectList();
                            });

                });
    }

    public Mono<Boolean> deleteGroup(String groupId) {
        return getGroupAndOrgMemberInfo(groupId)
                .filter(this::hasManagePermission)
                .switchIfEmpty(deferredError(BizError.NOT_AUTHORIZED, "NOT_AUTHORIZED"))
                .filterWhen(__ -> groupService.getById(groupId)
                        .map(Group::isNotSystemGroup))
                .switchIfEmpty(deferredError(BizError.CANNOT_DELETE_SYSTEM_GROUP, "CANNOT_DELETE_SYSTEM_GROUP"))
                .then(groupService.delete(groupId)
                        .thenReturn(true)
                );

    }

    public Mono<Group> create(CreateGroupRequest createGroupRequest) {
        return sessionUserService.getVisitorOrgMemberCache()
                .filter(OrgMember::isAdmin)
                .switchIfEmpty(deferredError(BizError.NOT_AUTHORIZED, "NOT_AUTHORIZED"))
                .transform(bizThresholdChecker::checkMaxGroupCount)
                .flatMap(orgMember -> {
                    String orgId = orgMember.getOrgId();
                    Group group = new Group();
                    group.setOrganizationId(orgId);
                    group.setName(createGroupRequest.getName());
                    return groupService.create(group, orgMember.getUserId(), orgMember.getOrgId());
                });
    }


    public Mono<Boolean> update(String groupId, UpdateGroupRequest updateGroupRequest) {
        return getGroupAndOrgMemberInfo(groupId)
                .filter(this::hasManagePermission)
                .switchIfEmpty(deferredError(BizError.NOT_AUTHORIZED, "NOT_AUTHORIZED"))
                .flatMap(it -> {
                    Group updateGroup = new Group();
                    updateGroup.setId(groupId);
                    updateGroup.setName(updateGroupRequest.getGroupName());
                    return groupService.updateGroup(updateGroup);
                });
    }

    public Mono<Boolean> removeUser(String groupId, String userId) {
        return getGroupAndOrgMemberInfo(groupId)
                .filter(this::hasManagePermission)
                .switchIfEmpty(deferredError(BizError.NOT_AUTHORIZED, "NOT_AUTHORIZED"))
                .flatMap(tuple -> {
                    String currentUserId = tuple.getT2().getUserId();
                    if (currentUserId.equals(userId)) {
                        return ofError(CANNOT_REMOVE_MYSELF, "CANNOT_REMOVE_MYSELF");
                    }
                    return groupMemberService.removeMember(groupId, userId);
                });
    }
}
