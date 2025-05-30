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
package com.cloud.domain.dao;

import java.util.List;
import java.util.Set;

import com.cloud.domain.DomainVO;
import com.cloud.user.Account;
import com.cloud.utils.db.GenericDao;

public interface DomainDao extends GenericDao<DomainVO, Long> {
    public DomainVO create(DomainVO domain);

    public DomainVO findDomainByPath(String domainPath);

    boolean isChildDomain(Long parentId, Long childId);

    DomainVO findImmediateChildForParent(Long parentId);

    List<DomainVO> findImmediateChildrenForParent(Long parentId);

    List<DomainVO> findAllChildren(String path, Long parentId);

    List<DomainVO> findInactiveDomains();

    Set<Long> getDomainParentIds(long domainId);

    List<Long> getDomainChildrenIds(String path);

    List<Long> getDomainAndChildrenIds(long domainId);

    boolean domainIdListContainsAccessibleDomain(String domainIdList, Account caller, Long domainId);
}
