/*
 * Copyright (C) 2023 The PixelExperience Project
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

package com.android.systemui.statusbar.policy;

import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.TextView;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.tuner.TunerService;

import java.text.DecimalFormat;
import java.util.ArrayList;


/** @hide */
public class StatusBarNetworkTraffic extends TextView implements DarkReceiver,
        StatusIconDisplayable, TunerService.Tunable {
    public static final String SLOT = "networktraffic";
    private static final String TAG = "NetworkTraffic";
    private static final int MODE_UPSTREAM_AND_DOWNSTREAM = 0;
    private static final int MODE_UPSTREAM_ONLY = 1;
    private static final int MODE_DOWNSTREAM_ONLY = 2;
    private static final int MESSAGE_TYPE_PERIODIC_REFRESH = 0;
    private static final int MESSAGE_TYPE_UPDATE_VIEW = 1;
    private static final int Kilo = 1000;
    private static final int Mega = Kilo * Kilo;
    private static final int Giga = Mega * Kilo;

    private static final String NETWORK_TRAFFIC_ENABLED =
            "system:" + Settings.System.NETWORK_TRAFFIC_ENABLED;
    private static final String NETWORK_TRAFFIC_AUTOHIDE =
            "system:" + Settings.System.NETWORK_TRAFFIC_AUTOHIDE;
    private static final String NETWORK_TRAFFIC_UNIT_TYPE =
            "system:" + Settings.System.NETWORK_TRAFFIC_UNIT_TYPE;

    private static final long AUTOHIDE_THRESHOLD = 10 * Kilo;

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private boolean mIsActive;

    private int mIconTint = 0;
    private int newTint = Color.WHITE;

    private boolean mAttached;
    private boolean mVisible = true;
    private boolean mEnabled = false;
    private boolean mConnectionAvailable = true;

    private int mSubMode = MODE_UPSTREAM_AND_DOWNSTREAM;
    private boolean mTrafficActive;

    private long mLastTxBytes;
    private long mLastRxBytes;
    private long mLastUpdateTime;
    private boolean mAutoHide;
    private int mUnitType;

    private Drawable mDrawable;

    private final int mRefreshInterval = 2;

    private ConnectivityManager mConnectivityManager;

    private final RelativeSizeSpan mSpeedRelativeSizeSpan = new RelativeSizeSpan(0.70f);
    private final RelativeSizeSpan mUnitRelativeSizeSpan = new RelativeSizeSpan(0.65f);

    private int mVisibleState = -1;
    private boolean mSystemIconVisible = true;

    private boolean mColorIsStatic;

    private boolean mKeyguardShowing;
    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardVisibilityChanged(boolean showing) {
                    mKeyguardShowing = showing;
                    updateVisibility();
                }
            };

    public StatusBarNetworkTraffic(Context context) {
        this(context, null);
    }

    public StatusBarNetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusBarNetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mConnectivityManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        setVisibleState(STATE_ICON);

        mKeyguardUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        mKeyguardUpdateMonitor.registerCallback(mUpdateCallback);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            final TunerService tunerService = Dependency.get(TunerService.class);
            tunerService.addTunable(this, NETWORK_TRAFFIC_ENABLED);
            tunerService.addTunable(this, NETWORK_TRAFFIC_AUTOHIDE);
            tunerService.addTunable(this, NETWORK_TRAFFIC_UNIT_TYPE);

            updateViews();
            setTrafficDrawable();
            setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

            mConnectionAvailable = mConnectivityManager.getActiveNetworkInfo() != null;

            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            mContext.registerReceiver(mIntentReceiver, filter, null, mTrafficHandler);
        }
        updateViews();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clearHandlerCallbacks();
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            Dependency.get(TunerService.class).removeTunable(this);
            mAttached = false;
        }
    }

    private final Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long rxBytes = 0;
            long txBytes = 0;

            if (msg.what == MESSAGE_TYPE_PERIODIC_REFRESH) {
                final long now = SystemClock.elapsedRealtime();
                long timeDelta = now - mLastUpdateTime; /* ms */

                if (timeDelta >= mRefreshInterval * 1000 * 0.95f) {
                    long[] newTotalRxTxBytes = getTotalRxTxBytes();

                    final long rxBytesDelta = newTotalRxTxBytes[0] - mLastRxBytes;
                    final long txBytesDelta = newTotalRxTxBytes[1] - mLastTxBytes;

                    rxBytes = (long) (rxBytesDelta / (timeDelta / 1000f));
                    txBytes = (long) (txBytesDelta / (timeDelta / 1000f));

                    mLastRxBytes = newTotalRxTxBytes[0];
                    mLastTxBytes = newTotalRxTxBytes[1];
                    mLastUpdateTime = now;
                }
            }

            final boolean aboveThreshold = (txBytes > AUTOHIDE_THRESHOLD)
                    || (rxBytes > AUTOHIDE_THRESHOLD);
            mIsActive = mAttached && mConnectionAvailable && (!mAutoHide || aboveThreshold);
            int submode = MODE_UPSTREAM_AND_DOWNSTREAM;
            final boolean trafficactive = (txBytes > 0 || rxBytes > 0);

            clearHandlerCallbacks();

            if (mEnabled && mIsActive) {
                CharSequence output = "";
                if (txBytes > rxBytes) {
                    output = formatOutput(txBytes);
                    submode = MODE_UPSTREAM_ONLY;
                } else if (txBytes < rxBytes) {
                    output = formatOutput(rxBytes);
                    submode = MODE_DOWNSTREAM_ONLY;
                } else {
                    output = formatOutput(rxBytes);
                    submode = MODE_UPSTREAM_AND_DOWNSTREAM;
                }

                // Update view if there's anything new to show
                if (output != getText()) {
                    setText(output);
                }
            }

            updateVisibility();

            if (mVisible && (mSubMode != submode ||
                    mTrafficActive != trafficactive)) {
                mSubMode = submode;
                mTrafficActive = trafficactive;
                setTrafficDrawable();
            }

            // Schedule periodic refresh
            if (mEnabled && mAttached) {
                mTrafficHandler.sendEmptyMessageDelayed(MESSAGE_TYPE_PERIODIC_REFRESH,
                        mRefreshInterval * 1000);
            }
        }

        private CharSequence formatOutput(long speed) {
            DecimalFormat decimalFormat;
            String unit;
            String formatSpeed;
            SpannableString spanUnitString;
            SpannableString spanSpeedString;
            String gunit, munit, kunit;

            if (mUnitType == 0) {
                gunit = mContext.getString(R.string.gigabytespersecond_short);
                munit = mContext.getString(R.string.megabytespersecond_short);
                kunit = mContext.getString(R.string.kilobytespersecond_short);
            } else {
                // speed is in bytes, convert to bits
                speed = speed * 8;
                gunit = mContext.getString(R.string.gigabitspersecond_short);
                munit = mContext.getString(R.string.megabitspersecond_short);
                kunit = mContext.getString(R.string.kilobitspersecond_short);
            }

            if (speed >= Giga) {
                unit = gunit;
                decimalFormat = new DecimalFormat("0.##");
                formatSpeed = decimalFormat.format(speed / (float) Giga);
            } else if (speed >= 100 * Mega) {
                decimalFormat = new DecimalFormat("##0");
                unit = munit;
                formatSpeed = decimalFormat.format(speed / (float) Mega);
            } else if (speed >= 10 * Mega) {
                decimalFormat = new DecimalFormat("#0.#");
                unit = munit;
                formatSpeed = decimalFormat.format(speed / (float) Mega);
            } else if (speed >= Mega) {
                decimalFormat = new DecimalFormat("0.##");
                unit = munit;
                formatSpeed = decimalFormat.format(speed / (float) Mega);
            } else if (speed >= 100 * Kilo) {
                decimalFormat = new DecimalFormat("##0");
                unit = kunit;
                formatSpeed = decimalFormat.format(speed / (float) Kilo);
            } else if (speed >= 10 * Kilo) {
                decimalFormat = new DecimalFormat("#0.#");
                unit = kunit;
                formatSpeed = decimalFormat.format(speed / (float) Kilo);
            } else {
                decimalFormat = new DecimalFormat("0.##");
                unit = kunit;
                formatSpeed = decimalFormat.format(speed / (float) Kilo);
            }
            spanSpeedString = new SpannableString(formatSpeed);
            spanSpeedString.setSpan(mSpeedRelativeSizeSpan, 0, (formatSpeed).length(),
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);

            spanUnitString = new SpannableString(unit);
            spanUnitString.setSpan(mUnitRelativeSizeSpan, 0, (unit).length(),
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return TextUtils.concat(spanSpeedString, "\n", spanUnitString);
        }

        private long[] getTotalRxTxBytes() {
            long[] bytes = new long[]{0, 0};

            // Sum tx and rx bytes from all sources of interest
            // Add stats
            bytes[0] = TrafficStats.getTotalRxBytes();
            bytes[1] = TrafficStats.getTotalTxBytes();

            return bytes;
        }
    };

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case NETWORK_TRAFFIC_ENABLED:
                mEnabled =
                        TunerService.parseIntegerSwitch(newValue, false);
                onEnableChange();
                if (mEnabled) {
                    setLines(2);
                    String txtFont = getResources().getString(R.string.config_bodyFontFamily);
                    setTypeface(Typeface.create(txtFont, Typeface.BOLD));
                    setLineSpacing(0.80f, 0.80f);
                }
                updateViews();
                break;
            case NETWORK_TRAFFIC_AUTOHIDE:
                mAutoHide =
                        TunerService.parseIntegerSwitch(newValue, false);
                updateViews();
                break;
            case NETWORK_TRAFFIC_UNIT_TYPE:
                mUnitType =
                        TunerService.parseInteger(newValue, 0);
                updateViews();
                break;
            default:
                break;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                mConnectionAvailable = mConnectivityManager.getActiveNetworkInfo() != null;
                updateViews();
            }
        }
    };

    private void updateViews() {
        if (mEnabled) {
            updateViewState();
        }
    }

    private void updateViewState() {
        mTrafficHandler.removeMessages(MESSAGE_TYPE_UPDATE_VIEW);
        mTrafficHandler.sendEmptyMessageDelayed(MESSAGE_TYPE_UPDATE_VIEW, 1000);
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
        mTrafficHandler.removeMessages(MESSAGE_TYPE_UPDATE_VIEW);
    }

    private void setTrafficDrawable() {
        final int drawableResId;
        final Drawable drawable;

        if (!mTrafficActive) {
            drawableResId = R.drawable.stat_sys_network_traffic;
        } else if (mSubMode == MODE_UPSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_up;
        } else if (mSubMode == MODE_DOWNSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_down;
        } else {
            drawableResId = R.drawable.stat_sys_network_traffic_updown;
        }
        drawable = drawableResId != 0 ? getResources().getDrawable(drawableResId) : null;
        if (mDrawable != drawable || mIconTint != newTint) {
            mDrawable = drawable;
            mIconTint = newTint;
            setCompoundDrawablesWithIntrinsicBounds(null, null, mDrawable, null);
            updateTrafficDrawable();
        }
    }

    private void updateTrafficDrawable() {
        if (mDrawable != null) {
            mDrawable.setColorFilter(
                    new PorterDuffColorFilter(mIconTint, PorterDuff.Mode.MULTIPLY));
        }
        setTextColor(mIconTint);
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        if (mColorIsStatic) {
            return;
        }
        newTint = DarkIconDispatcher.getTint(areas, this, tint);
        checkUpdateTrafficDrawable();
    }

    @Override
    public void setStaticDrawableColor(int color) {
        mColorIsStatic = true;
        newTint = color;
        checkUpdateTrafficDrawable();
    }

    @Override
    public void setDecorColor(int color) {
    }

    @Override
    public String getSlot() {
        return SLOT;
    }

    @Override
    public boolean isIconVisible() {
        return mEnabled;
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public void setVisibleState(int state, boolean animate) {
        if (state == mVisibleState || !mEnabled || !mAttached) {
            return;
        }
        mVisibleState = state;

        switch (state) {
            case STATE_ICON:
                mSystemIconVisible = true;
                break;
            case STATE_DOT:
            case STATE_HIDDEN:
            default:
                mSystemIconVisible = false;
                break;
        }
    }

    private void onEnableChange() {
    }

    private void updateVisibility() {
        boolean visible = mEnabled && mIsActive && !mKeyguardShowing && mSystemIconVisible
                && getText() != "";
        if (visible != mVisible) {
            mVisible = visible;
            setVisibility(mVisible ? VISIBLE : GONE);
            checkUpdateTrafficDrawable();
        }
    }

    private void checkUpdateTrafficDrawable() {
        // Wait for icon to be visible and tint to be changed
        if (mVisible && mIconTint != newTint) {
            mIconTint = newTint;
            updateTrafficDrawable();
        }
    }
}
