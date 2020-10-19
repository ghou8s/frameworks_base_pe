/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.bubbles;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_EXPANDED_VIEW;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.systemui.bubbles.BubbleOverflowActivity.EXTRA_BUBBLE_CONTROLLER;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ShapeDrawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.recents.TriangleShape;
import com.android.systemui.statusbar.AlphaOptimizedButton;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Container for the expanded bubble view, handles rendering the caret and settings icon.
 */
public class BubbleExpandedView extends LinearLayout {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleExpandedView" : TAG_BUBBLES;

    // The triangle pointing to the expanded view
    private View mPointerView;
    private int mPointerMargin;
    @Nullable private int[] mExpandedViewContainerLocation;

    private AlphaOptimizedButton mSettingsIcon;
    private TaskView mTaskView;

    private int mTaskId = INVALID_TASK_ID;

    private boolean mImeVisible;
    private boolean mNeedsNewHeight;

    private int mMinHeight;
    private int mOverflowHeight;
    private int mSettingsIconHeight;
    private int mPointerWidth;
    private int mPointerHeight;
    private ShapeDrawable mCurrentPointer;
    private ShapeDrawable mTopPointer;
    private ShapeDrawable mLeftPointer;
    private ShapeDrawable mRightPointer;
    private int mExpandedViewPadding;
    private float mCornerRadius = 0f;

    @Nullable private Bubble mBubble;
    private PendingIntent mPendingIntent;
    // TODO(b/170891664): Don't use a flag, set the BubbleOverflow object instead
    private boolean mIsOverflow;

    private Bubbles mBubbles = Dependency.get(Bubbles.class);
    private BubbleStackView mStackView;
    private BubblePositioner mPositioner;

    /**
     * Container for the ActivityView that has a solid, round-rect background that shows if the
     * ActivityView hasn't loaded.
     */
    private final FrameLayout mExpandedViewContainer = new FrameLayout(getContext());

    private final TaskView.Listener mTaskViewListener = new TaskView.Listener() {
        private boolean mInitialized = false;
        private boolean mDestroyed = false;

        @Override
        public void onInitialized() {
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "onActivityViewReady: destroyed=" + mDestroyed
                        + " initialized=" + mInitialized
                        + " bubble=" + getBubbleKey());
            }

            if (mDestroyed || mInitialized) {
                return;
            }
            // Custom options so there is no activity transition animation
            ActivityOptions options = ActivityOptions.makeCustomAnimation(getContext(),
                    0 /* enterResId */, 0 /* exitResId */);

            // TODO: I notice inconsistencies in lifecycle
            // Post to keep the lifecycle normal
            post(() -> {
                if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                    Log.d(TAG, "onActivityViewReady: calling startActivity, bubble="
                            + getBubbleKey());
                }
                try {
                    if (!mIsOverflow && mBubble.hasMetadataShortcutId()) {
                        mTaskView.startShortcutActivity(mBubble.getShortcutInfo(),
                                options, null /* sourceBounds */);
                    } else {
                        Intent fillInIntent = new Intent();
                        // Apply flags to make behaviour match documentLaunchMode=always.
                        fillInIntent.addFlags(FLAG_ACTIVITY_NEW_DOCUMENT);
                        fillInIntent.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                        if (mBubble != null) {
                            mBubble.setIntentActive();
                        }
                        mTaskView.startActivity(mPendingIntent, fillInIntent, options);
                    }
                } catch (RuntimeException e) {
                    // If there's a runtime exception here then there's something
                    // wrong with the intent, we can't really recover / try to populate
                    // the bubble again so we'll just remove it.
                    Log.w(TAG, "Exception while displaying bubble: " + getBubbleKey()
                            + ", " + e.getMessage() + "; removing bubble");
                    mBubbles.removeBubble(getBubbleKey(),
                            BubbleController.DISMISS_INVALID_INTENT);
                }
            });
            mInitialized = true;
        }

        @Override
        public void onReleased() {
            mDestroyed = true;
        }

        @Override
        public void onTaskCreated(int taskId, ComponentName name) {
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "onTaskCreated: taskId=" + taskId
                        + " bubble=" + getBubbleKey());
            }
            // The taskId is saved to use for removeTask, preventing appearance in recent tasks.
            mTaskId = taskId;

            // With the task org, the taskAppeared callback will only happen once the task has
            // already drawn
            setContentVisibility(true);
        }

        @Override
        public void onTaskVisibilityChanged(int taskId, boolean visible) {
            setContentVisibility(visible);
        }

        @Override
        public void onTaskRemovalStarted(int taskId) {
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "onTaskRemovalStarted: taskId=" + taskId
                        + " bubble=" + getBubbleKey());
            }
            if (mBubble != null) {
                // Must post because this is called from a binder thread.
                post(() -> mBubbles.removeBubble(mBubble.getKey(),
                        BubbleController.DISMISS_TASK_FINISHED));
            }
        }

        @Override
        public void onBackPressedOnTaskRoot(int taskId) {
            if (mTaskId == taskId && mStackView.isExpanded()) {
                mBubbles.collapseStack();
            }
        }
    };

    public BubbleExpandedView(Context context) {
        this(context, null);
    }

    public BubbleExpandedView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleExpandedView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BubbleExpandedView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        updateDimensions();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        Resources res = getResources();
        mPointerView = findViewById(R.id.pointer_view);
        mPointerWidth = res.getDimensionPixelSize(R.dimen.bubble_pointer_width);
        mPointerHeight = res.getDimensionPixelSize(R.dimen.bubble_pointer_height);

        mTopPointer = new ShapeDrawable(TriangleShape.create(
                mPointerWidth, mPointerHeight, true /* pointUp */));
        mLeftPointer = new ShapeDrawable(TriangleShape.createHorizontal(
                mPointerWidth, mPointerHeight, true /* pointLeft */));
        mRightPointer = new ShapeDrawable(TriangleShape.createHorizontal(
                mPointerWidth, mPointerHeight, false /* pointLeft */));

        mCurrentPointer = mTopPointer;
        mPointerView.setVisibility(INVISIBLE);

        mSettingsIconHeight = getContext().getResources().getDimensionPixelSize(
                R.dimen.bubble_manage_button_height);
        mSettingsIcon = findViewById(R.id.settings_button);

        mPositioner = mBubbles.getPositioner();

        mTaskView = new TaskView(mContext, mBubbles.getTaskManager());
        // Set ActivityView's alpha value as zero, since there is no view content to be shown.
        setContentVisibility(false);

        mExpandedViewContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCornerRadius);
            }
        });
        mExpandedViewContainer.setClipToOutline(true);
        mExpandedViewContainer.addView(mTaskView);
        mExpandedViewContainer.setLayoutParams(
                new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        addView(mExpandedViewContainer);

        // Expanded stack layout, top to bottom:
        // Expanded view container
        // ==> bubble row
        // ==> expanded view
        //   ==> activity view
        //   ==> manage button
        bringChildToFront(mTaskView);
        bringChildToFront(mSettingsIcon);
        mTaskView.setListener(mTaskViewListener);

        applyThemeAttrs();

        mExpandedViewPadding = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_padding);
        setClipToPadding(false);
        setOnTouchListener((view, motionEvent) -> {
            if (mTaskView == null) {
                return false;
            }

            final Rect avBounds = new Rect();
            mTaskView.getBoundsOnScreen(avBounds);

            // Consume and ignore events on the expanded view padding that are within the
            // ActivityView's vertical bounds. These events are part of a back gesture, and so they
            // should not collapse the stack (which all other touches on areas around the AV would
            // do).
            if (motionEvent.getRawY() >= avBounds.top
                            && motionEvent.getRawY() <= avBounds.bottom
                            && (motionEvent.getRawX() < avBounds.left
                                || motionEvent.getRawX() > avBounds.right)) {
                return true;
            }

            return false;
        });

        // BubbleStackView is forced LTR, but we want to respect the locale for expanded view layout
        // so the Manage button appears on the right.
        setLayoutDirection(LAYOUT_DIRECTION_LOCALE);
    }

    void updateDimensions() {
        Resources res = getResources();
        mMinHeight = res.getDimensionPixelSize(R.dimen.bubble_expanded_default_height);
        mOverflowHeight = res.getDimensionPixelSize(R.dimen.bubble_overflow_height);
        mPointerMargin = res.getDimensionPixelSize(R.dimen.bubble_pointer_margin);
    }

    void applyThemeAttrs() {
        final TypedArray ta = mContext.obtainStyledAttributes(new int[] {
                android.R.attr.dialogCornerRadius,
                android.R.attr.colorBackgroundFloating});
        mCornerRadius = ta.getDimensionPixelSize(0, 0);
        mExpandedViewContainer.setBackgroundColor(ta.getColor(1, Color.WHITE));
        ta.recycle();

        if (mTaskView != null && ScreenDecorationsUtils.supportsRoundedCornersOnWindows(
                mContext.getResources())) {
            mTaskView.setCornerRadius(mCornerRadius);
        }
        updatePointerView();
    }

    private void updatePointerView() {
        final int mode =
                getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        switch (mode) {
            case Configuration.UI_MODE_NIGHT_NO:
                mCurrentPointer.setTint(getResources().getColor(R.color.bubbles_light));
                break;
            case Configuration.UI_MODE_NIGHT_YES:
                mCurrentPointer.setTint(getResources().getColor(R.color.bubbles_dark));
                break;
        }
        LayoutParams lp = (LayoutParams) mPointerView.getLayoutParams();
        if (mCurrentPointer == mLeftPointer || mCurrentPointer == mRightPointer) {
            lp.width = mPointerHeight;
            lp.height = mPointerWidth;
        } else {
            lp.width = mPointerWidth;
            lp.height = mPointerHeight;
        }
        mPointerView.setLayoutParams(lp);
        mPointerView.setBackground(mCurrentPointer);
    }


    private String getBubbleKey() {
        return mBubble != null ? mBubble.getKey() : "null";
    }

    /**
     * Sets whether the surface displaying app content should sit on top. This is useful for
     * ordering surfaces during animations. When content is drawn on top of the app (e.g. bubble
     * being dragged out, the manage menu) this is set to false, otherwise it should be true.
     */
    void setSurfaceZOrderedOnTop(boolean onTop) {
        if (mTaskView == null) {
            return;
        }
        mTaskView.setZOrderedOnTop(onTop, true /* allowDynamicChange */);
    }

    void setImeVisible(boolean visible) {
        mImeVisible = visible;
        if (!mImeVisible && mNeedsNewHeight) {
            updateHeight();
        }
    }

    /** Return a GraphicBuffer with the contents of the task view surface. */
    @Nullable
    SurfaceControl.ScreenshotHardwareBuffer snapshotActivitySurface() {
        if (mTaskView == null) {
            return null;
        }
        return SurfaceControl.captureLayers(
                mTaskView.getSurfaceControl(),
                new Rect(0, 0, mTaskView.getWidth(), mTaskView.getHeight()),
                1 /* scale */);
    }

    int[] getTaskViewLocationOnScreen() {
        if (mTaskView != null) {
            return mTaskView.getLocationOnScreen();
        } else {
            return new int[]{0, 0};
        }
    }

    // TODO: Could listener be passed when we pass StackView / can we avoid setting this like this
    void setManageClickListener(OnClickListener manageClickListener) {
        mSettingsIcon.setOnClickListener(manageClickListener);
    }

    /**
     * Updates the obscured touchable region for the task surface. This calls onLocationChanged,
     * which results in a call to {@link BubbleStackView#subtractObscuredTouchableRegion}. This is
     * useful if a view has been added or removed from on top of the ActivityView, such as the
     * manage menu.
     */
    void updateObscuredTouchableRegion() {
        if (mTaskView != null) {
            mTaskView.onLocationChanged();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mImeVisible = false;
        mNeedsNewHeight = false;
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "onDetachedFromWindow: bubble=" + getBubbleKey());
        }
    }

    /**
     * Set visibility of contents in the expanded state.
     *
     * @param visibility {@code true} if the contents should be visible on the screen.
     *
     * Note that this contents visibility doesn't affect visibility at {@link android.view.View},
     * and setting {@code false} actually means rendering the contents in transparent.
     */
    void setContentVisibility(boolean visibility) {
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "setContentVisibility: visibility=" + visibility
                    + " bubble=" + getBubbleKey());
        }
        final float alpha = visibility ? 1f : 0f;

        mPointerView.setAlpha(alpha);
        if (mTaskView == null) {
            return;
        }
        if (alpha != mTaskView.getAlpha()) {
            mTaskView.setAlpha(alpha);
        }
    }

    @Nullable
    View getTaskView() {
        return mTaskView;
    }

    int getTaskId() {
        return mTaskId;
    }

    void setStackView(BubbleStackView stackView) {
        mStackView = stackView;
    }

    public void setOverflow(boolean overflow) {
        mIsOverflow = overflow;

        Intent target = new Intent(mContext, BubbleOverflowActivity.class);
        Bundle extras = new Bundle();
        extras.putBinder(EXTRA_BUBBLE_CONTROLLER, ObjectWrapper.wrap(mBubbles));
        target.putExtras(extras);
        mPendingIntent = PendingIntent.getActivity(mContext, 0 /* requestCode */,
                target, PendingIntent.FLAG_UPDATE_CURRENT);
        mSettingsIcon.setVisibility(GONE);
    }

    /**
     * Sets the bubble used to populate this view.
     */
    void update(Bubble bubble) {
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "update: bubble=" + bubble);
        }
        if (mStackView == null) {
            Log.w(TAG, "Stack is null for bubble: " + bubble);
            return;
        }
        boolean isNew = mBubble == null || didBackingContentChange(bubble);
        if (isNew || bubble != null && bubble.getKey().equals(mBubble.getKey())) {
            mBubble = bubble;
            mSettingsIcon.setContentDescription(getResources().getString(
                    R.string.bubbles_settings_button_description, bubble.getAppName()));
            mSettingsIcon.setAccessibilityDelegate(
                    new AccessibilityDelegate() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(View host,
                                AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            // On focus, have TalkBack say
                            // "Actions available. Use swipe up then right to view."
                            // in addition to the default "double tap to activate".
                            mStackView.setupLocalMenu(info);
                        }
                    });

            if (isNew) {
                mPendingIntent = mBubble.getBubbleIntent();
                if (mPendingIntent != null || mBubble.hasMetadataShortcutId()) {
                    setContentVisibility(false);
                    mTaskView.setVisibility(VISIBLE);
                }
            }
            applyThemeAttrs();
        } else {
            Log.w(TAG, "Trying to update entry with different key, new bubble: "
                    + bubble.getKey() + " old bubble: " + bubble.getKey());
        }
    }

    /**
     * Bubbles are backed by a pending intent or a shortcut, once the activity is
     * started we never change it / restart it on notification updates -- unless the bubbles'
     * backing data switches.
     *
     * This indicates if the new bubble is backed by a different data source than what was
     * previously shown here (e.g. previously a pending intent & now a shortcut).
     *
     * @param newBubble the bubble this view is being updated with.
     * @return true if the backing content has changed.
     */
    private boolean didBackingContentChange(Bubble newBubble) {
        boolean prevWasIntentBased = mBubble != null && mPendingIntent != null;
        boolean newIsIntentBased = newBubble.getBubbleIntent() != null;
        return prevWasIntentBased != newIsIntentBased;
    }

    void updateHeight() {
        if (mExpandedViewContainerLocation == null) {
            return;
        }

        if (mBubble != null || mIsOverflow) {
            float desiredHeight = mIsOverflow
                    ? mOverflowHeight
                    : mBubble.getDesiredHeight(mContext);
            desiredHeight = Math.max(desiredHeight, mMinHeight);
            float height = Math.min(desiredHeight, getMaxExpandedHeight());
            height = Math.max(height, mMinHeight);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mTaskView.getLayoutParams();
            mNeedsNewHeight = lp.height != height;
            if (!mImeVisible) {
                // If the ime is visible... don't adjust the height because that will cause
                // a configuration change and the ime will be lost.
                lp.height = (int) height;
                mTaskView.setLayoutParams(lp);
                mNeedsNewHeight = false;
            }
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "updateHeight: bubble=" + getBubbleKey()
                        + " height=" + height
                        + " mNeedsNewHeight=" + mNeedsNewHeight);
            }
        }
    }

    private int getMaxExpandedHeight() {
        int expandedContainerY = mExpandedViewContainerLocation != null
                // Remove top insets back here because availableRect.height would account for that
                ? mExpandedViewContainerLocation[1] - mPositioner.getInsets().top
                : 0;
        return mPositioner.getAvailableRect().height()
                - expandedContainerY
                - getPaddingTop()
                - getPaddingBottom()
                - mSettingsIconHeight
                - mPointerHeight
                - mPointerMargin;
    }

    /**
     * Update appearance of the expanded view being displayed.
     *
     * @param containerLocationOnScreen The location on-screen of the container the expanded view is
     *                                  added to. This allows us to calculate max height without
     *                                  waiting for layout.
     */
    public void updateView(int[] containerLocationOnScreen) {
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "updateView: bubble="
                    + getBubbleKey());
        }
        mExpandedViewContainerLocation = containerLocationOnScreen;
        if (mTaskView != null
                && mTaskView.getVisibility() == VISIBLE
                && mTaskView.isAttachedToWindow()) {
            updateHeight();
            mTaskView.onLocationChanged();
        }
    }

    /**
     * Set the position that the tip of the triangle should point to.
     */
    public void setPointerPosition(float x, float y, boolean isLandscape, boolean onLeft) {
        // Pointer gets drawn in the padding
        int paddingLeft = (isLandscape && onLeft) ? mPointerHeight : 0;
        int paddingRight = (isLandscape && !onLeft) ? mPointerHeight : 0;
        int paddingTop = isLandscape ? 0 : mExpandedViewPadding;
        setPadding(paddingLeft, paddingTop, paddingRight, 0);

        if (isLandscape) {
            // TODO: why setY vs setTranslationY ? linearlayout?
            mPointerView.setY(y - (mPointerWidth / 2f));
            mPointerView.setTranslationX(onLeft ? -mPointerHeight : x - mExpandedViewPadding);
        } else {
            mPointerView.setTranslationY(0f);
            mPointerView.setTranslationX(x - mExpandedViewPadding - (mPointerWidth / 2f));
        }
        mCurrentPointer = isLandscape ? onLeft ? mLeftPointer : mRightPointer : mTopPointer;
        updatePointerView();
        mPointerView.setVisibility(VISIBLE);
    }

    /**
     * Position of the manage button displayed in the expanded view. Used for placing user
     * education about the manage button.
     */
    public void getManageButtonBoundsOnScreen(Rect rect) {
        mSettingsIcon.getBoundsOnScreen(rect);
    }

    /**
     * Cleans up anything related to the task and TaskView.
     */
    public void cleanUpExpandedState() {
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "cleanUpExpandedState: bubble=" + getBubbleKey() + " task=" + mTaskId);
        }
        if (mTaskView != null) {
            mTaskView.release();
        }
        if (mTaskView != null) {
            removeView(mTaskView);
            mTaskView = null;
        }
    }

    /**
     * Description of current expanded view state.
     */
    public void dump(
            @NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.print("BubbleExpandedView");
        pw.print("  taskId:               "); pw.println(mTaskId);
        pw.print("  stackView:            "); pw.println(mStackView);
    }
}
