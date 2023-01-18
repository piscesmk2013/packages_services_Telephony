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

package com.android.services.telephony.domainselection;

import static android.telephony.AccessNetworkConstants.AccessNetworkType.CDMA2000;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.EUTRAN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.NGRAN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.UNKNOWN;
import static android.telephony.AccessNetworkConstants.AccessNetworkType.UTRAN;
import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_INVALID;
import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WLAN;
import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
import static android.telephony.BarringInfo.BARRING_SERVICE_TYPE_EMERGENCY;
import static android.telephony.CarrierConfigManager.ImsEmergency.DOMAIN_CS;
import static android.telephony.CarrierConfigManager.ImsEmergency.DOMAIN_PS_3GPP;
import static android.telephony.CarrierConfigManager.ImsEmergency.DOMAIN_PS_NON_3GPP;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_CALL_SETUP_TIMER_ON_CURRENT_NETWORK_SEC_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_CDMA_PREFERRED_NUMBERS_STRING_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_DOMAIN_PREFERENCE_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_DOMAIN_PREFERENCE_ROAMING_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_LTE_PREFERRED_AFTER_NR_FAILED_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_NETWORK_SCAN_TYPE_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_CS_ROAMING_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_CS_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_IMS_ROAMING_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_OVER_IMS_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_REQUIRES_IMS_REGISTRATION_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_REQUIRES_VOLTE_ENABLED_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_EMERGENCY_SCAN_TIMER_SEC_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_MAXIMUM_NUMBER_OF_EMERGENCY_TRIES_OVER_VOWIFI_INT;
import static android.telephony.CarrierConfigManager.ImsEmergency.KEY_PREFER_IMS_EMERGENCY_WHEN_VOICE_CALLS_ON_CS_BOOL;
import static android.telephony.CarrierConfigManager.ImsEmergency.SCAN_TYPE_FULL_SERVICE_FOLLOWED_BY_LIMITED_SERVICE;
import static android.telephony.NetworkRegistrationInfo.REGISTRATION_STATE_HOME;
import static android.telephony.NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING;

import android.annotation.NonNull;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.AccessNetworkConstants.RadioAccessNetworkType;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.BarringInfo;
import android.telephony.CarrierConfigManager;
import android.telephony.DomainSelectionService;
import android.telephony.DomainSelectionService.SelectionAttributes;
import android.telephony.EmergencyRegResult;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.TransportSelectorCallback;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.text.TextUtils;
import android.util.LocalLog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Selects the domain for emergency calling.
 */
public class EmergencyCallDomainSelector extends DomainSelectorBase
        implements ImsStateTracker.BarringInfoListener, ImsStateTracker.ImsStateListener {
    private static final String TAG = "DomainSelector-EmergencyCall";
    private static final boolean DBG = (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final int LOG_SIZE = 50;

    private static final int MSG_START_DOMAIN_SELECTION = 11;
    @VisibleForTesting
    public static final int MSG_NETWORK_SCAN_TIMEOUT = 12;
    private static final int MSG_NETWORK_SCAN_RESULT = 13;

    private static final int NOT_SUPPORTED = -1;

    private static final LocalLog sLocalLog = new LocalLog(LOG_SIZE);

    private boolean mIsEmergencyBarred;
    private boolean mImsRegistered;
    private boolean mIsVoiceCapable;
    private boolean mBarringInfoReceived;
    private boolean mImsRegStateReceived;
    private boolean mMmTelCapabilitiesReceived;
    private int mVoWifiTrialCount = 0;

    private @RadioAccessNetworkType int mCsNetworkType = UNKNOWN;
    private @RadioAccessNetworkType int mPsNetworkType = UNKNOWN;
    private @RadioAccessNetworkType int mLastNetworkType = UNKNOWN;
    private @TransportType int mLastTransportType = TRANSPORT_TYPE_INVALID;
    private @DomainSelectionService.EmergencyScanType int mScanType;
    private @RadioAccessNetworkType List<Integer> mLastPreferredNetworks;

    private CancellationSignal mCancelSignal;

    private @RadioAccessNetworkType int[] mImsRatsConfig;
    private @RadioAccessNetworkType int[] mCsRatsConfig;
    private @RadioAccessNetworkType int[] mImsRoamRatsConfig;
    private @RadioAccessNetworkType int[] mCsRoamRatsConfig;
    private @CarrierConfigManager.ImsEmergency.EmergencyDomain int[] mDomainPreference;
    private @CarrierConfigManager.ImsEmergency.EmergencyDomain int[] mDomainPreferenceRoam;
    private List<String> mCdmaPreferredNumbers;
    private boolean mPreferImsWhenCallsOnCs;
    private int mScanTimeout;
    private int mMaxNumOfVoWifiTries;
    private @CarrierConfigManager.ImsEmergency.EmergencyScanType int mPreferredNetworkScanType;
    private int mCallSetupTimerOnCurrentRat;
    private boolean mRequiresImsRegistration;
    private boolean mRequiresVoLteEnabled;
    private boolean mLtePreferredAfterNrFailure;
    private boolean mTryCsWhenPsFails;

    /** Indicates whether this instance is deactivated. */
    private boolean mDestroyed = false;
    /** Indicates whether emergency network scan is requested. */
    private boolean mIsScanRequested = false;
    /** Indicates whether selected domain has been notified. */
    private boolean mDomainSelected = false;
    /**
     * Indicates whether {@link #selectDomain(SelectionAttributes, TransportSelectionCallback)}
     * is called or not.
     */
    private boolean mDomainSelectionRequested = false;

    private final PowerManager.WakeLock mPartialWakeLock;

    /** Constructor. */
    public EmergencyCallDomainSelector(Context context, int slotId, int subId,
            @NonNull Looper looper, @NonNull ImsStateTracker imsStateTracker,
            @NonNull DestroyListener destroyListener) {
        super(context, slotId, subId, looper, imsStateTracker, destroyListener, TAG);

        mImsStateTracker.addBarringInfoListener(this);
        mImsStateTracker.addImsStateListener(this);

        PowerManager pm = context.getSystemService(PowerManager.class);
        mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        acquireWakeLock();
    }

    @Override
    public void handleMessage(Message msg) {
        if (mDestroyed) return;

        switch(msg.what) {
            case MSG_START_DOMAIN_SELECTION:
                startDomainSelection();
                break;

            case MSG_NETWORK_SCAN_TIMEOUT:
                handleNetworkScanTimeout();
                break;

            case MSG_NETWORK_SCAN_RESULT:
                handleScanResult((EmergencyRegResult) msg.obj);
                break;

            default:
                super.handleMessage(msg);
                break;
        }
    }

    /**
     * Handles the scan result.
     *
     * @param result The scan result.
     */
    private void handleScanResult(EmergencyRegResult result) {
        logi("handleScanResult result=" + result);

        if (result.getAccessNetwork() == UNKNOWN) {
            if ((mPreferredNetworkScanType == SCAN_TYPE_FULL_SERVICE_FOLLOWED_BY_LIMITED_SERVICE)
                      || (mScanType == DomainSelectionService.SCAN_TYPE_FULL_SERVICE)) {
                mScanType = DomainSelectionService.SCAN_TYPE_LIMITED_SERVICE;
                mWwanSelectorCallback.onRequestEmergencyNetworkScan(
                        mLastPreferredNetworks, mScanType, mCancelSignal,
                        (regResult) -> {
                            logi("requestScan-onComplete");
                            sendMessage(obtainMessage(MSG_NETWORK_SCAN_RESULT, regResult));
                        });
            } else {
                // Continuous scan, do not start a new timer.
                requestScan(false);
            }
            return;
        }

        removeMessages(MSG_NETWORK_SCAN_TIMEOUT);
        onWwanNetworkTypeSelected(result.getAccessNetwork());
        mIsScanRequested = false;
    }

    @Override
    public void cancelSelection() {
        logi("cancelSelection");
        finishSelection();
    }

    @Override
    public void reselectDomain(SelectionAttributes attr) {
        logi("reselectDomain attr=" + attr);
        mSelectionAttributes = attr;
        post(() -> { reselectDomain(); });
    }

    private void reselectDomain() {
        logi("reselectDomain tryCsWhenPsFails=" + mTryCsWhenPsFails);

        if (mTryCsWhenPsFails) {
            mTryCsWhenPsFails = false;
            mCsNetworkType = getSelectableCsNetworkType();
            logi("reselectDomain tryCs=" + accessNetworkTypeToString(mCsNetworkType));
            if (mCsNetworkType != UNKNOWN) {
                onWwanNetworkTypeSelected(mCsNetworkType);
                return;
            }
        } else if (getImsNetworkTypeConfiguration().isEmpty()
                || (mRequiresVoLteEnabled && !isAdvancedCallingSettingEnabled())) {
            // Emergency call over IMS is not supported.
            mCsNetworkType = UTRAN;
            onWwanNetworkTypeSelected(mCsNetworkType);
            return;
        }

        if (mLastTransportType == TRANSPORT_TYPE_WLAN) {
            // Dialing over Wi-Fi failed. Try scanning cellular networks.
            onWwanSelected(this::reselectDomainInternal);
            return;
        }

        requestScan(true);
        mDomainSelected = false;
    }

    private void reselectDomainInternal() {
        post(() -> {
            requestScan(true, false, true);
            mDomainSelected = false;
        });
    }

    @Override
    public void finishSelection() {
        logi("finishSelection");
        destroy();
    }

    @Override
    public void onBarringInfoUpdated(BarringInfo barringInfo) {
        if (mDestroyed) return;

        mBarringInfoReceived = true;
        BarringInfo.BarringServiceInfo serviceInfo =
                barringInfo.getBarringServiceInfo(BARRING_SERVICE_TYPE_EMERGENCY);
        mIsEmergencyBarred = serviceInfo.isBarred();
        logi("onBarringInfoUpdated emergencyBarred=" + mIsEmergencyBarred
                + ", serviceInfo=" + serviceInfo);
        selectDomain();
    }

    @Override
    public void selectDomain(SelectionAttributes attr, TransportSelectorCallback cb) {
        logi("selectDomain attr=" + attr);
        mTransportSelectorCallback = cb;
        mSelectionAttributes = attr;

        sendEmptyMessage(MSG_START_DOMAIN_SELECTION);
    }

    private void startDomainSelection() {
        logi("startDomainSelection");
        updateCarrierConfiguration();
        mDomainSelectionRequested = true;
        if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
            selectDomain();
        } else {
            logi("startDomainSelection invalid subId");
            onImsRegistrationStateChanged();
            onImsMmTelCapabilitiesChanged();
        }
    }

    @Override
    public void onImsMmTelFeatureAvailableChanged() {
        // DOMAIN_CS shall be selected when ImsService is not available.
        // TODO(b/258289015) Recover the temporary failure in ImsService connection.
    }

    @Override
    public void onImsRegistrationStateChanged() {
        mImsRegStateReceived = true;
        mImsRegistered = mImsStateTracker.isImsRegistered();
        logi("onImsRegistrationStateChanged " + mImsRegistered);
        selectDomain();
    }

    @Override
    public void onImsMmTelCapabilitiesChanged() {
        mMmTelCapabilitiesReceived = true;
        mIsVoiceCapable = mImsStateTracker.isImsVoiceCapable();
        logi("onImsMmTelCapabilitiesChanged " + mIsVoiceCapable);
        selectDomain();
    }

    /**
     * Caches the configuration.
     */
    private void updateCarrierConfiguration() {
        CarrierConfigManager configMgr = mContext.getSystemService(CarrierConfigManager.class);
        PersistableBundle b = configMgr.getConfigForSubId(getSubId());
        if (b == null) {
            b = CarrierConfigManager.getDefaultConfig();
        }

        mImsRatsConfig =
                b.getIntArray(KEY_EMERGENCY_OVER_IMS_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY);
        mImsRoamRatsConfig = b.getIntArray(
                KEY_EMERGENCY_OVER_IMS_ROAMING_SUPPORTED_3GPP_NETWORK_TYPES_INT_ARRAY);
        if (!SubscriptionManager.isValidSubscriptionId(getSubId())) {
            // Default configuration includes only EUTRAN . In case of no SIM, add NGRAN.
            mImsRatsConfig = new int[] { EUTRAN, NGRAN };
            mImsRoamRatsConfig = new int[] { EUTRAN, NGRAN };
        }

        mCsRatsConfig =
                b.getIntArray(KEY_EMERGENCY_OVER_CS_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY);
        mCsRoamRatsConfig = b.getIntArray(
                KEY_EMERGENCY_OVER_CS_ROAMING_SUPPORTED_ACCESS_NETWORK_TYPES_INT_ARRAY);
        mDomainPreference = b.getIntArray(KEY_EMERGENCY_DOMAIN_PREFERENCE_INT_ARRAY);
        mDomainPreferenceRoam = b.getIntArray(KEY_EMERGENCY_DOMAIN_PREFERENCE_ROAMING_INT_ARRAY);
        mPreferImsWhenCallsOnCs = b.getBoolean(
                KEY_PREFER_IMS_EMERGENCY_WHEN_VOICE_CALLS_ON_CS_BOOL);
        mScanTimeout = b.getInt(KEY_EMERGENCY_SCAN_TIMER_SEC_INT) * 1000;
        mMaxNumOfVoWifiTries = b.getInt(KEY_MAXIMUM_NUMBER_OF_EMERGENCY_TRIES_OVER_VOWIFI_INT);
        mPreferredNetworkScanType = b.getInt(KEY_EMERGENCY_NETWORK_SCAN_TYPE_INT);
        mCallSetupTimerOnCurrentRat = b.getInt(
                KEY_EMERGENCY_CALL_SETUP_TIMER_ON_CURRENT_NETWORK_SEC_INT) * 1000;
        mRequiresImsRegistration = b.getBoolean(KEY_EMERGENCY_REQUIRES_IMS_REGISTRATION_BOOL);
        mRequiresVoLteEnabled = b.getBoolean(KEY_EMERGENCY_REQUIRES_VOLTE_ENABLED_BOOL);
        mLtePreferredAfterNrFailure = b.getBoolean(
                KEY_EMERGENCY_LTE_PREFERRED_AFTER_NR_FAILED_BOOL);
        String[] numbers = b.getStringArray(KEY_EMERGENCY_CDMA_PREFERRED_NUMBERS_STRING_ARRAY);

        if (mImsRatsConfig == null) mImsRatsConfig = new int[0];
        if (mCsRatsConfig == null) mCsRatsConfig = new int[0];
        if (mImsRoamRatsConfig == null) mImsRoamRatsConfig = new int[0];
        if (mCsRoamRatsConfig == null) mCsRoamRatsConfig = new int[0];
        if (mDomainPreference == null) mDomainPreference = new int[0];
        if (mDomainPreferenceRoam == null) mDomainPreferenceRoam = new int[0];
        if (numbers == null) numbers = new String[0];

        logi("updateCarrierConfiguration "
                + "imsRats=" + arrayToString(mImsRatsConfig,
                        EmergencyCallDomainSelector::accessNetworkTypeToString)
                + ", csRats=" + arrayToString(mCsRatsConfig,
                        EmergencyCallDomainSelector::accessNetworkTypeToString)
                + ", imsRoamRats=" + arrayToString(mImsRoamRatsConfig,
                        EmergencyCallDomainSelector::accessNetworkTypeToString)
                + ", csRoamRats=" + arrayToString(mCsRoamRatsConfig,
                        EmergencyCallDomainSelector::accessNetworkTypeToString)
                + ", domainPref=" + arrayToString(mDomainPreference,
                        EmergencyCallDomainSelector::domainPreferenceToString)
                + ", domainPrefRoam=" + arrayToString(mDomainPreferenceRoam,
                        EmergencyCallDomainSelector::domainPreferenceToString)
                + ", preferImsOnCs=" + mPreferImsWhenCallsOnCs
                + ", scanTimeout=" + mScanTimeout
                + ", maxNumOfVoWifiTries=" + mMaxNumOfVoWifiTries
                + ", preferredScanType=" + carrierConfigNetworkScanTypeToString(
                        mPreferredNetworkScanType)
                + ", callSetupTimer=" + mCallSetupTimerOnCurrentRat
                + ", requiresImsReg=" + mRequiresImsRegistration
                + ", requiresVoLteEnabled=" + mRequiresVoLteEnabled
                + ", ltePreferredAfterNr=" + mLtePreferredAfterNrFailure
                + ", cdmaPreferredNumbers=" + arrayToString(numbers));

        mCdmaPreferredNumbers = Arrays.asList(numbers);

        if ((mPreferredNetworkScanType == CarrierConfigManager.ImsEmergency.SCAN_TYPE_FULL_SERVICE)
                || (mPreferredNetworkScanType
                        == SCAN_TYPE_FULL_SERVICE_FOLLOWED_BY_LIMITED_SERVICE)) {
            mScanType = DomainSelectionService.SCAN_TYPE_FULL_SERVICE;
        } else {
            mScanType = DomainSelectionService.SCAN_TYPE_NO_PREFERENCE;
        }
    }

    private void selectDomain() {
        // State updated right after creation.
        if (!mDomainSelectionRequested) return;

        // Emergency network scan requested has not been completed.
        if (mIsScanRequested) return;

        // Domain selection completed, {@link #reselectDomain()} will restart domain selection.
        if (mDomainSelected) return;

        if (!mBarringInfoReceived || !mImsRegStateReceived || !mMmTelCapabilitiesReceived) {
            logi("selectDomain not received"
                    + " BarringInfo, IMS registration state, or MMTEL capabilities");
            return;
        }

        if (isWifiPreferred()) {
            onWlanSelected();
            return;
        }

        onWwanSelected(this::selectDomainInternal);
    }

    private void selectDomainInternal() {
        post(this::selectDomainFromInitialState);
    }

    private void selectDomainFromInitialState() {
        if (getImsNetworkTypeConfiguration().isEmpty()
                || (mRequiresVoLteEnabled && !isAdvancedCallingSettingEnabled())) {
            // Emergency call over IMS is not supported.
            mCsNetworkType = UTRAN;
            onWwanNetworkTypeSelected(mCsNetworkType);
            return;
        }

        boolean csInService = isCsInService();
        boolean psInService = isPsInService();

        if (!csInService && !psInService) {
            mPsNetworkType = getSelectablePsNetworkType(false);
            logi("selectDomain limited service ps=" + accessNetworkTypeToString(mPsNetworkType));
            if (mPsNetworkType == UNKNOWN) {
                requestScan(true);
            } else {
                onWwanNetworkTypeSelected(mPsNetworkType);
            }
            return;
        }

        // Domain selection per 3GPP TS 23.167 Table H.1.
        // PS is preferred in case selection between CS and PS is implementation option.
        mCsNetworkType = UNKNOWN;
        mPsNetworkType = UNKNOWN;
        if (csInService) mCsNetworkType = getSelectableCsNetworkType();
        if (psInService) mPsNetworkType = getSelectablePsNetworkType(true);

        boolean csAvailable = mCsNetworkType != UNKNOWN;
        boolean psAvailable = mPsNetworkType != UNKNOWN;

        logi("selectDomain CS={" + csInService + ", " + accessNetworkTypeToString(mCsNetworkType)
                + "}, PS={" + psInService + ", " + accessNetworkTypeToString(mPsNetworkType) + "}");
        if (csAvailable && psAvailable) {
            if (mPreferImsWhenCallsOnCs || isImsRegisteredWithVoiceCapability()) {
                mTryCsWhenPsFails = true;
                onWwanNetworkTypeSelected(mPsNetworkType);
            } else if (isDeactivatedSim()) {
                // Deactivated SIM but PS is in service and supports emergency calls.
                onWwanNetworkTypeSelected(mPsNetworkType);
            } else {
                onWwanNetworkTypeSelected(mCsNetworkType);
            }
        } else if (psAvailable) {
            if (!mRequiresImsRegistration || isImsRegisteredWithVoiceCapability()) {
                onWwanNetworkTypeSelected(mPsNetworkType);
            } else if (isDeactivatedSim()) {
                // Deactivated SIM but PS is in service and supports emergency calls.
                onWwanNetworkTypeSelected(mPsNetworkType);
            } else {
                // Carrier configuration requires IMS registration for emergency services over PS,
                // but not registered. Try CS emergency call.
                requestScan(true, true);
            }
        } else if (csAvailable) {
            onWwanNetworkTypeSelected(mCsNetworkType);
        } else {
            // PS is in service but not supports emergency calls.
            if (mRequiresImsRegistration && !isImsRegisteredWithVoiceCapability()) {
                // Carrier configuration requires IMS registration for emergency services over PS,
                // but not registered. Try CS emergency call.
                requestScan(true, true);
            } else {
                requestScan(true);
            }
        }
    }

    /**
     * Requests network scan.
     *
     * @param startVoWifiTimer Indicates whether a VoWifi timer will be started.
     */
    private void requestScan(boolean startVoWifiTimer) {
        requestScan(startVoWifiTimer, false);
    }

    /**
     * Requests network scan.
     *
     * @param startVoWifiTimer Indicates whether a VoWifi timer will be started.
     * @param csPreferred Indicates whether CS preferred scan is requested.
     */
    private void requestScan(boolean startVoWifiTimer, boolean csPreferred) {
        requestScan(startVoWifiTimer, csPreferred, false);
    }

    /**
     * Requests network scan.
     *
     * @param startVoWifiTimer Indicates whether a VoWifi timer will be started.
     * @param csPreferred Indicates whether CS preferred scan is requested.
     * @param wifiFailed Indicates dialing over Wi-Fi has failed.
     */
    private void requestScan(boolean startVoWifiTimer, boolean csPreferred, boolean wifiFailed) {
        logi("requestScan timer=" + startVoWifiTimer + ", csPreferred=" + csPreferred
                + ", wifiFailed=" + wifiFailed);

        mCancelSignal = new CancellationSignal();
        // In case dialing over Wi-Fi has failed, do not the change the domain preference.
        if (!wifiFailed) mLastPreferredNetworks = getNextPreferredNetworks(csPreferred);

        if (isInRoaming()
                && (mPreferredNetworkScanType == DomainSelectionService.SCAN_TYPE_FULL_SERVICE)) {
            // FULL_SERVICE only preference is available only when not in roaming.
            mScanType = DomainSelectionService.SCAN_TYPE_NO_PREFERENCE;
        }

        mIsScanRequested = true;
        mWwanSelectorCallback.onRequestEmergencyNetworkScan(
                mLastPreferredNetworks, mScanType, mCancelSignal,
                (result) -> {
                    logi("requestScan-onComplete");
                    sendMessage(obtainMessage(MSG_NETWORK_SCAN_RESULT, result));
                });

        if (startVoWifiTimer && SubscriptionManager.isValidSubscriptionId(getSubId())) {
            if (isEmcOverWifiSupported()
                    && mScanTimeout > 0 && mVoWifiTrialCount < mMaxNumOfVoWifiTries) {
                logi("requestScan start scan timer");
                // remove any pending timers.
                removeMessages(MSG_NETWORK_SCAN_TIMEOUT);
                sendEmptyMessageDelayed(MSG_NETWORK_SCAN_TIMEOUT, mScanTimeout);
            }
        }
    }

    /**
     * Gets the list of preferred network type for the new scan request.
     *
     * @param csPreferred Indicates whether CS preferred scan is requested.
     * @return The list of preferred network types.
     */
    private @RadioAccessNetworkType List<Integer> getNextPreferredNetworks(boolean csPreferred) {
        List<Integer> preferredNetworks = new ArrayList<>();

        List<Integer> domains = getDomainPreference();
        int psPriority = domains.indexOf(DOMAIN_PS_3GPP);
        int csPriority = domains.indexOf(DOMAIN_CS);
        logi("getNextPreferredNetworks psPriority=" + psPriority + ", csPriority=" + csPriority
                + ", csPreferred=" + csPreferred
                + ", lastNetworkType=" + accessNetworkTypeToString(mLastNetworkType));

        if (!csPreferred && mLastNetworkType == UNKNOWN) {
            // Generate the list per the domain preference.

            if (psPriority == NOT_SUPPORTED && csPriority == NOT_SUPPORTED) {
                // should not reach here.
            } else if (psPriority == NOT_SUPPORTED && csPriority > NOT_SUPPORTED) {
                // CS networks only.
                preferredNetworks = generatePreferredNetworks(getCsNetworkTypeConfiguration());
            } else if (psPriority > NOT_SUPPORTED && csPriority == NOT_SUPPORTED) {
                // PS networks only.
                preferredNetworks = generatePreferredNetworks(getImsNetworkTypeConfiguration());
            } else if (psPriority < csPriority) {
                // PS preferred.
                preferredNetworks = generatePreferredNetworks(getImsNetworkTypeConfiguration(),
                        getCsNetworkTypeConfiguration());
            } else {
                // CS preferred.
                generatePreferredNetworks(getCsNetworkTypeConfiguration(),
                        getImsNetworkTypeConfiguration());
            }
        } else if (csPreferred || mLastNetworkType == EUTRAN || mLastNetworkType == NGRAN) {
            if (!csPreferred && mLastNetworkType == NGRAN && mLtePreferredAfterNrFailure) {
                // LTE is preferred after dialing over NR failed.
                List<Integer> imsRats = getImsNetworkTypeConfiguration();
                imsRats.remove(new Integer(NGRAN));
                preferredNetworks = generatePreferredNetworks(imsRats,
                        getCsNetworkTypeConfiguration());
            } else  if (csPriority > NOT_SUPPORTED) {
                // PS tried, generate the list with CS preferred.
                preferredNetworks = generatePreferredNetworks(getCsNetworkTypeConfiguration(),
                        getImsNetworkTypeConfiguration());
            } else {
                // CS not suppored.
                generatePreferredNetworks(getImsNetworkTypeConfiguration());
            }
        } else {
            // CS tried, generate the list with PS preferred.
            if (psPriority > NOT_SUPPORTED) {
                preferredNetworks = generatePreferredNetworks(getImsNetworkTypeConfiguration(),
                        getCsNetworkTypeConfiguration());
            } else {
                // PS not suppored.
                preferredNetworks = generatePreferredNetworks(getCsNetworkTypeConfiguration());
            }
        }

        return preferredNetworks;
    }

    private @RadioAccessNetworkType List<Integer> generatePreferredNetworks(List<Integer>...lists) {
        List<Integer> preferredNetworks = new ArrayList<>();
        for (List<Integer> list : lists) {
            preferredNetworks.addAll(list);
        }

        return preferredNetworks;
    }

    private void handleNetworkScanTimeout() {
        if (isImsRegisteredWithVoiceCapability()
                && isImsRegisteredOverWifi()) {
            if (mCancelSignal != null) {
                mCancelSignal.cancel();
                mCancelSignal = null;
            }
            onWlanSelected();
        }
    }

    /**
     * Determines whether CS is in service.
     *
     * @return {@code true} if CS is in service.
     */
    private boolean isCsInService() {
        EmergencyRegResult regResult = mSelectionAttributes.getEmergencyRegResult();
        if (regResult == null) return false;

        int regState = regResult.getRegState();
        int domain = regResult.getDomain();

        if ((regState == REGISTRATION_STATE_HOME || regState == REGISTRATION_STATE_ROAMING)
                && ((domain & NetworkRegistrationInfo.DOMAIN_CS) > 0)) {
            return true;
        }

        return false;
    }

    /**
     * Determines the network type of the circuit-switched(CS) network.
     *
     * @return The network type of the CS network.
     */
    private @RadioAccessNetworkType int getSelectableCsNetworkType() {
        EmergencyRegResult regResult = mSelectionAttributes.getEmergencyRegResult();
        logi("getSelectableCsNetworkType regResult=" + regResult);
        if (regResult == null) return UNKNOWN;

        int accessNetwork = regResult.getAccessNetwork();

        List<Integer> ratList = getCsNetworkTypeConfiguration();
        if (ratList.contains(accessNetwork)) {
            return accessNetwork;
        }

        if ((regResult.getAccessNetwork() == EUTRAN)
                && ((regResult.getDomain() & NetworkRegistrationInfo.DOMAIN_CS) > 0)) {
            return UTRAN;
        }

        return UNKNOWN;
    }

    /**
     * Determines whether PS is in service.
     *
     * @return {@code true} if PS is in service.
     */
    private boolean isPsInService() {
        EmergencyRegResult regResult = mSelectionAttributes.getEmergencyRegResult();
        if (regResult == null) return false;

        int regState = regResult.getRegState();
        int domain = regResult.getDomain();

        if ((regState == REGISTRATION_STATE_HOME || regState == REGISTRATION_STATE_ROAMING)
                && ((domain & NetworkRegistrationInfo.DOMAIN_PS) > 0)) {
            return true;
        }

        return false;
    }

    /**
     * Determines the network type supporting emergency services over packet-switched(PS) network.
     *
     * @param inService Indicates whether PS is IN_SERVICE state.
     * @return The network type if the network supports emergency services over PS network.
     */
    private @RadioAccessNetworkType int getSelectablePsNetworkType(boolean inService) {
        EmergencyRegResult regResult = mSelectionAttributes.getEmergencyRegResult();
        logi("getSelectablePsNetworkType regResult=" + regResult);
        if (regResult == null) return UNKNOWN;

        int accessNetwork = regResult.getAccessNetwork();
        List<Integer> ratList = getImsNetworkTypeConfiguration();
        if (ratList.contains(accessNetwork)) {
            if (mIsEmergencyBarred) {
                logi("sgetSelectablePsNetworkType barred");
                return UNKNOWN;
            }
            if (accessNetwork == NGRAN) {
                return (regResult.getNwProvidedEmc() > 0 && regResult.isVopsSupported())
                        ? NGRAN : UNKNOWN;
            } else if (accessNetwork == EUTRAN) {
                return (regResult.isEmcBearerSupported()
                                && (regResult.isVopsSupported() || !inService))
                        ? EUTRAN : UNKNOWN;
            }
        }

        return UNKNOWN;
    }

    /**
     * Determines whether the SIM is a deactivated one.
     *
     * @return {@code true} if the SIM is a deactivated one.
     */
    private boolean isDeactivatedSim() {
        if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
            TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
            tm = tm.createForSubscriptionId(getSubId());
            int state = tm.getDataActivationState();
            logi("isDeactivatedSim state=" + state);
            return (state == TelephonyManager.SIM_ACTIVATION_STATE_DEACTIVATED);
        }
        return false;
    }

    /**
     * Determines whether emergency call over Wi-Fi is allowed.
     *
     * @return {@code true} if emergency call over Wi-Fi allowed.
     */
    private boolean isEmcOverWifiSupported() {
        if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
            List<Integer> domains = getDomainPreference();
            return domains.contains(DOMAIN_PS_NON_3GPP);
        }
        return false;
    }

    /**
     * Determines whether Wi-Fi is preferred when IMS registered over Wi-Fi.
     *
     * @return {@code true} if Wi-Fi is preferred when IMS registered over Wi-Fi.
     */
    private boolean isWifiPreferred() {
        if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
            List<Integer> domains = getDomainPreference();
            int priority = domains.indexOf(DOMAIN_PS_NON_3GPP);
            logi("isWifiPreferred priority=" + priority);

            if ((priority == 0)
                    && isImsRegisteredWithVoiceCapability()
                    && isImsRegisteredOverWifi()) {
                logi("isWifiPreferred try emergency call over Wi-Fi");
                return true;
            }
        }

        return false;
    }

    private boolean isAdvancedCallingSettingEnabled() {
        try {
            if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
                ImsManager imsMngr = mContext.getSystemService(ImsManager.class);
                ImsMmTelManager mmTelManager = imsMngr.getImsMmTelManager(getSubId());
                boolean result = mmTelManager.isAdvancedCallingSettingEnabled();
                logi("isAdvancedCallingSettingEnabled " + result);
                return result;
            }
        } catch (Exception e) {
            logi("isAdvancedCallingSettingEnabled e=" + e);
        }
        return true;
    }

    private @NonNull List<Integer> getImsNetworkTypeConfiguration() {
        int[] rats = mImsRatsConfig;
        if (isInRoaming()) rats = mImsRoamRatsConfig;

        List<Integer> ratList = new ArrayList<Integer>();
        for (int i = 0; i < rats.length; i++) {
            ratList.add(rats[i]);
        }
        return ratList;
    }

    private @NonNull List<Integer> getCsNetworkTypeConfiguration() {
        int[] rats = mCsRatsConfig;
        if (isInRoaming()) rats = mCsRoamRatsConfig;

        List<Integer> ratList = new ArrayList<Integer>();
        for (int i = 0; i < rats.length; i++) {
            ratList.add(rats[i]);
        }

        if (!mCdmaPreferredNumbers.isEmpty()) {
            if (mCdmaPreferredNumbers.contains(mSelectionAttributes.getNumber())) {
                // The number will be dialed over CDMA.
                ratList.clear();
                ratList.add(new Integer(CDMA2000));
            } else {
                // The number will be dialed over UTRAN or GERAN.
                ratList.remove(new Integer(CDMA2000));
            }
        }

        return ratList;
    }

    private @NonNull List<Integer> getDomainPreference() {
        int[] domains = mDomainPreference;
        if (isInRoaming()) domains = mDomainPreferenceRoam;

        List<Integer> domainList = new ArrayList<Integer>();
        for (int i = 0; i < domains.length; i++) {
            domainList.add(domains[i]);
        }
        return domainList;
    }

    private boolean isInRoaming() {
        if (!SubscriptionManager.isValidSubscriptionId(getSubId())) return false;

        TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        tm = tm.createForSubscriptionId(getSubId());
        String netIso = tm.getNetworkCountryIso();

        EmergencyRegResult regResult = mSelectionAttributes.getEmergencyRegResult();
        if (regResult != null) {
            if (regResult.getRegState() == REGISTRATION_STATE_HOME) return false;
            if (regResult.getRegState() == REGISTRATION_STATE_ROAMING) return true;

            String iso = regResult.getIso();
            if (!TextUtils.isEmpty(iso)) netIso = iso;
        }

        String simIso = tm.getSimCountryIso();
        logi("isInRoaming simIso=" + simIso + ", netIso=" + netIso);

        if (TextUtils.isEmpty(simIso)) return false;
        if (TextUtils.isEmpty(netIso)) return false;

        return !(TextUtils.equals(simIso, netIso));
    }

    /**
     * Determines whether IMS is registered over Wi-Fi.
     *
     * @return {@code true} if IMS is registered over Wi-Fi.
     */
    private boolean isImsRegisteredOverWifi() {
        boolean ret = false;
        if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
            ret = mImsStateTracker.isImsRegisteredOverWlan();
        }

        logi("isImsRegisteredOverWifi " + ret);
        return ret;
    }

    /**
     * Determines whether IMS is registered with voice capability.
     *
     * @return {@code true} if IMS is registered with voice capability.
     */
    private boolean isImsRegisteredWithVoiceCapability() {
        boolean ret = mImsRegistered && mIsVoiceCapable;

        logi("isImsRegisteredWithVoiceCapability " + ret);
        return ret;
    }

    private void onWlanSelected() {
        logi("onWlanSelected");
        if (mLastTransportType == TRANSPORT_TYPE_WLAN) {
            logi("onWlanSelected ignore duplicated callback");
            return;
        }

        mDomainSelected = true;
        mLastTransportType = TRANSPORT_TYPE_WLAN;
        mVoWifiTrialCount++;
        mTransportSelectorCallback.onWlanSelected();
        mWwanSelectorCallback = null;
    }

    private void onWwanSelected(Runnable runnable) {
        logi("onWwanSelected");
        if (mLastTransportType == TRANSPORT_TYPE_WWAN) {
            logi("onWwanSelected ignore duplicated callback");
            return;
        }

        mLastTransportType = TRANSPORT_TYPE_WWAN;
        mTransportSelectorCallback.onWwanSelected((callback) -> {
            mWwanSelectorCallback = callback;
            runnable.run();
        });
    }

    private void onWwanNetworkTypeSelected(@RadioAccessNetworkType int accessNetworkType) {
        logi("onWwanNetworkTypeSelected " + accessNetworkTypeToString(accessNetworkType));
        if (mWwanSelectorCallback == null) {
            logi("onWwanNetworkTypeSelected callback is null");
            return;
        }

        mDomainSelected = true;
        mLastNetworkType = accessNetworkType;
        int domain = NetworkRegistrationInfo.DOMAIN_CS;
        if (accessNetworkType == EUTRAN || accessNetworkType == NGRAN) {
            domain = NetworkRegistrationInfo.DOMAIN_PS;
        }
        mWwanSelectorCallback.onDomainSelected(domain);
    }

    private static String arrayToString(int[] intArray, IntFunction<String> func) {
        int length = intArray.length;
        StringBuilder sb = new StringBuilder("{");
        if (length > 0) {
            int i = 0;
            sb.append(func.apply(intArray[i++]));
            while (i < length) {
                sb.append(", ").append(func.apply(intArray[i++]));
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String arrayToString(String[] stringArray) {
        StringBuilder sb;
        int length = stringArray.length;
        sb = new StringBuilder("{");
        if (length > 0) {
            int i = 0;
            sb.append(stringArray[i++]);
            while (i < length) {
                sb.append(", ").append(stringArray[i++]);
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String domainPreferenceToString(
            @CarrierConfigManager.ImsEmergency.EmergencyDomain int domain) {
        switch (domain) {
            case DOMAIN_CS: return "CS";
            case DOMAIN_PS_3GPP: return "PS_3GPP";
            case DOMAIN_PS_NON_3GPP: return "PS_NON_3GPP";
            default: return "UNKNOWN";
        }
    }

    private static String carrierConfigNetworkScanTypeToString(
            @CarrierConfigManager.ImsEmergency.EmergencyScanType int scanType) {
        switch (scanType) {
            case CarrierConfigManager.ImsEmergency.SCAN_TYPE_NO_PREFERENCE: return "NO_PREF";
            case CarrierConfigManager.ImsEmergency.SCAN_TYPE_FULL_SERVICE: return "FULL";
            case SCAN_TYPE_FULL_SERVICE_FOLLOWED_BY_LIMITED_SERVICE: return "FULL_N_LIMITED";
            default: return "UNKNOWN";
        }
    }

    private static String accessNetworkTypeToString(
            @RadioAccessNetworkType int accessNetworkType) {
        switch (accessNetworkType) {
            case AccessNetworkType.UNKNOWN: return "UNKNOWN";
            case AccessNetworkType.GERAN: return "GERAN";
            case AccessNetworkType.UTRAN: return "UTRAN";
            case AccessNetworkType.EUTRAN: return "EUTRAN";
            case AccessNetworkType.CDMA2000: return "CDMA2000";
            case AccessNetworkType.IWLAN: return "IWLAN";
            case AccessNetworkType.NGRAN: return "NGRAN";
            default: return Integer.toString(accessNetworkType);
        }
    }

    /**
     * Destroys the instance.
     */
    @VisibleForTesting
    public void destroy() {
        if (DBG) logd("destroy");

        releaseWakeLock();

        mDestroyed = true;
        mImsStateTracker.removeBarringInfoListener(this);
        mImsStateTracker.removeImsStateListener(this);

        super.destroy();
    }

    private void acquireWakeLock() {
        if (mPartialWakeLock != null) {
            synchronized (mPartialWakeLock) {
                logi("acquireWakeLock");
                mPartialWakeLock.acquire();
            }
        }
    }

    private void releaseWakeLock() {
        if (mPartialWakeLock != null) {
            synchronized (mPartialWakeLock) {
                if (mPartialWakeLock.isHeld()) {
                    logi("releaseWakeLock");
                    mPartialWakeLock.release();
                }
            }
        }
    }

    @Override
    protected void logi(String msg) {
        super.logi(msg);
        sLocalLog.log(msg);
    }

    @Override
    protected void loge(String msg) {
        super.loge(msg);
        sLocalLog.log(msg);
    }
}
