// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.projects;

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.cloud.utils.db.GenericDao;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;

@Entity
@Table(name = "project_invitations")
public class ProjectInvitationVO implements ProjectInvitation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "project_id")
    private long projectId;

    @Column(name = "account_id")
    private Long forAccountId;

    @Column(name = "domain_id")
    private Long inDomainId;

    @Column(name = "account_role")
    @Enumerated(value = EnumType.STRING)
    private ProjectAccount.Role accountRole = ProjectAccount.Role.Regular;

    @Column(name = "project_role_id")
    private Long projectRoleId;

    @Column(name = "token")
    private String token;

    @Column(name = "email")
    private String email;

    @Column(name = "state")
    @Enumerated(value = EnumType.STRING)
    private State state = State.Pending;

    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "user_id")
    private Long forUserId;

    protected ProjectInvitationVO() {
        uuid = UUID.randomUUID().toString();
    }

    public ProjectInvitationVO(long projectId, Long accountId, Long domainId, String email, String token) {
        forAccountId = accountId;
        inDomainId = domainId;
        this.projectId = projectId;
        this.email = email;
        this.token = token;
        uuid = UUID.randomUUID().toString();
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public long getProjectId() {
        return projectId;
    }

    @Override
    public Long getForAccountId() {
        return forAccountId;
    }

    @Override
    public String getToken() {
        return token;
    }

    @Override
    public String getEmail() {
        return email;
    }

    @Override
    public Date getCreated() {
        return created;
    }

    @Override
    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    @Override
    public String toString() {
        return String.format("ProjectInvitation %s.",
                ReflectionToStringBuilderUtils.reflectOnlySelectedFields(
                        this, "id", "uuid", "projectId", "forAccountId"));
    }

    @Override
    public Long getInDomainId() {
        return inDomainId;
    }

    @Override
    public ProjectAccount.Role getAccountRole() {
        return accountRole;
    }

    public void setAccountRole(ProjectAccount.Role accountRole) {
        this.accountRole = accountRole;
    }

    @Override
    public Long getProjectRoleId() {
        return projectRoleId;
    }

    public void setProjectRoleId(Long projectRoleId) {
        this.projectRoleId = projectRoleId;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    @Override
    public long getDomainId() {
        return inDomainId == null ? -1 : inDomainId;
    }

    @Override
    public long getAccountId() {
        return forAccountId == null ? -1 : forAccountId;
    }

    @Override
    public Long getForUserId() {
        return forUserId == null ? -1 : forUserId;
    }

    public void setForUserId(Long forUserId) {
        this.forUserId = forUserId;
    }

    @Override
    public Class<?> getEntityType() {
        return ProjectInvitation.class;
    }

    @Override
    public String getName() {
        return null;
    }
}
