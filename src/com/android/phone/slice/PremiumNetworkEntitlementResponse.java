/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone.slice;

import android.annotation.IntDef;

class PremiumNetworkEntitlementResponse {

    public static final int PREMIUM_NETWORK_ENTITLEMENT_STATUS_DISABLED = 0;
    public static final int PREMIUM_NETWORK_ENTITLEMENT_STATUS_ENABLED = 1;
    public static final int PREMIUM_NETWORK_ENTITLEMENT_STATUS_INCOMPATIBLE = 2;
    public static final int PREMIUM_NETWORK_ENTITLEMENT_STATUS_PROVISIONING = 3;
    public static final int PREMIUM_NETWORK_ENTITLEMENT_STATUS_INCLUDED = 4;

    @IntDef(prefix = {"PREMIUM_NETWORK_ENTITLEMENT_STATUS_"},
            value = {
                    PREMIUM_NETWORK_ENTITLEMENT_STATUS_DISABLED,
                    PREMIUM_NETWORK_ENTITLEMENT_STATUS_ENABLED,
                    PREMIUM_NETWORK_ENTITLEMENT_STATUS_INCOMPATIBLE,
                    PREMIUM_NETWORK_ENTITLEMENT_STATUS_PROVISIONING,
                    PREMIUM_NETWORK_ENTITLEMENT_STATUS_INCLUDED
            })
    public @interface PremiumNetworkEntitlementStatus {}

    public static final int PREMIUM_NETWORK_PROVISION_STATUS_NOT_PROVISIONED = 0;
    public static final int PREMIUM_NETWORK_PROVISION_STATUS_PROVISIONED = 1;
    public static final int PREMIUM_NETWORK_PROVISION_STATUS_NOT_REQUIRED = 2;
    public static final int PREMIUM_NETWORK_PROVISION_STATUS_IN_PROGRESS = 3;

    @IntDef(prefix = {"PREMIUM_NETWORK_PROVISION_STATUS_"},
            value = {
                    PREMIUM_NETWORK_PROVISION_STATUS_NOT_PROVISIONED,
                    PREMIUM_NETWORK_PROVISION_STATUS_PROVISIONED,
                    PREMIUM_NETWORK_PROVISION_STATUS_NOT_REQUIRED,
                    PREMIUM_NETWORK_PROVISION_STATUS_IN_PROGRESS
            })
    public @interface PremiumNetworkProvisionStatus {}

    @PremiumNetworkEntitlementStatus int mEntitlementStatus;
    @PremiumNetworkProvisionStatus int mProvisionStatus;
    int mProvisionTimeLeftInSeconds;
    String mServiceFlowURL;

    boolean isProvisioned() {
        if (mProvisionStatus == PREMIUM_NETWORK_PROVISION_STATUS_PROVISIONED
                || mEntitlementStatus == PREMIUM_NETWORK_ENTITLEMENT_STATUS_INCLUDED) {
            return true;
        }
        return false;
    }

    boolean isProvisioningInProgress() {
        if (mProvisionStatus == PREMIUM_NETWORK_PROVISION_STATUS_IN_PROGRESS
                || mEntitlementStatus == PREMIUM_NETWORK_ENTITLEMENT_STATUS_PROVISIONING) {
            return true;
        }
        return false;
    }

    boolean isPremiumNetworkCapabilityAllowed() {
        switch (mEntitlementStatus) {
            case PREMIUM_NETWORK_ENTITLEMENT_STATUS_INCOMPATIBLE:
            case PREMIUM_NETWORK_ENTITLEMENT_STATUS_DISABLED:
                return false;
        }
        return true;
    }
}
