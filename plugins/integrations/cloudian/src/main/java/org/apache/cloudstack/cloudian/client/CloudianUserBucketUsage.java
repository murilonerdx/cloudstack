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

package org.apache.cloudstack.cloudian.client;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudianUserBucketUsage {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CloudianBucketUsage {
        private String bucketName;
        private Long   byteCount;
        private Long   objectCount;
        private String policyName;

        /**
         * Get the name of the bucket the usage stats belong to
         * @return the bucket name
         */
        public String getBucketName() {
            return bucketName;
        }
        public void setBucketName(String bucketName) {
            this.bucketName = bucketName;
        }

        /**
         * Get the number of bytes used by this bucket.
         *
         * Note: This size includes bucket and object metadata.
         *
         * @return bytes used by the bucket
         */
        public Long getByteCount() {
            return byteCount;
        }
        public void setByteCount(Long byteCount) {
            this.byteCount = byteCount;
        }

        /**
         * Get the number of objects stored in the bucket.
         *
         * @return object count in the bucket
         */
        public Long getObjectCount() {
            return objectCount;
        }
        public void setObjectCount(Long objectCount) {
            this.objectCount = objectCount;
        }

        /**
         * Get the storage policy this bucket belongs to.
         * @return the name of the HyperStore storage policy.
         */
        public String getPolicyName() {
            return policyName;
        }
        public void setPolicyName(String policyName) {
            this.policyName = policyName;
        }
    }

    private String userId;
    private List<CloudianBucketUsage> buckets;

    /**
     * Get the HyperStore userId this usage info belongs to
     * @return the HyperStore userId
     */
    public String getUserId() {
        return userId;
    }
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Get the list of bucket usage objects belonging to this HyperStore userId.
     * @return list of bucket usage objects.
     */
    public List<CloudianBucketUsage> getBuckets() {
        return buckets;
    }
    public void setBuckets(List<CloudianBucketUsage> buckets) {
        this.buckets = buckets;
    }
}
