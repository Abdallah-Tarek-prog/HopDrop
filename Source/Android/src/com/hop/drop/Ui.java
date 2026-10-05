package com.hop.drop;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small platform-view kit shared by the tabs. Colors are semantic tokens from res/values(-night)/colors.xml. */
final class Ui {
    static final int PRIMARY = 0;
    static final int TONAL = 1;
    static final int OUTLINE = 2;
    static final int DANGER = 3;

    final MainActivity activity;
    final int ink;
    final int blue;
    final int buttonBlue;
    final int muted;
    final int surface;
    final int card;
    final int surfaceAlt;
    final int border;
    final int white;
    final int primaryContainer;
    final int onPrimaryContainer;
    final int success;
    final int successContainer;
    final int danger;
    final int dangerContainer;
    final int amber;
    final int amberContainer;
    final int onAmberContainer;

    Ui(MainActivity activity) {
        this.activity = activity;
        ink = activity.getColor(R.color.navy);
        blue = activity.getColor(R.color.blue);
        buttonBlue = activity.getColor(R.color.button_blue);
        muted = activity.getColor(R.color.muted);
        surface = activity.getColor(R.color.surface);
        card = activity.getColor(R.color.card_surface);
        surfaceAlt = activity.getColor(R.color.surface_alt);
        border = activity.getColor(R.color.border);
        white = activity.getColor(R.color.white);
        primaryContainer = activity.getColor(R.color.primary_container);
        onPrimaryContainer = activity.getColor(R.color.on_primary_container);
        success = activity.getColor(R.color.success);
        successContainer = activity.getColor(R.color.success_container);
        danger = activity.getColor(R.color.danger);
        dangerContainer = activity.getColor(R.color.danger_container);
        amber = activity.getColor(R.color.amber);
        amberContainer = activity.getColor(R.color.amber_container);
        onAmberContainer = activity.getColor(R.color.on_amber_container);
    }

    int dp(float size) {
        return (int) (size * activity.getResources().getDisplayMetrics().density + .5f);
    }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    LinearLayout row() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    TextView text(String value, int size, int color, boolean strong) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.12f);
        if (strong) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    ImageView icon(int resource, int tint, String description) {
        ImageView view = new ImageView(activity);
        view.setImageResource(resource);
        view.setImageTintList(ColorStateList.valueOf(tint));
        view.setContentDescription(description);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return view;
    }

    /** An icon centered in a tinted circle (or rounded square when {@code round} is false). */
    ImageView badge(int resource, int tint, int background, int sizeDp, boolean round) {
        ImageView view = icon(resource, tint, null);
        int pad = Math.round(sizeDp * 0.24f);
        view.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        view.setBackground(shape(background, round ? sizeDp / 2 : 12));
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return view;
    }

    void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    GradientDrawable outlined(int color, int stroke, int radius) {
        GradientDrawable drawable = shape(color, radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    View dot(int color) {
        View view = new View(activity);
        view.setBackground(shape(color, 5));
        return view;
    }

    /** Pressable background with a ripple in the brand color. */
    void background(View view, int color, int radius) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(blue & 0x29ffffff), shape(color, radius),
                shape(0xff000000, radius)));
    }

    /** A flat card: white/navy surface, hairline border, 20 dp corners. */
    LinearLayout card(LinearLayout page, String title) {
        LinearLayout view = column();
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        view.setBackground(outlined(card, border, 20));
        add(page, view, 12);
        if (title != null) add(view, text(title, 17, ink, true), 0);
        return view;
    }

    /** Card whose title row has a small text action on the right, e.g. "Clear". */
    LinearLayout card(LinearLayout page, String title, String action, Runnable run) {
        LinearLayout view = card(page, null);
        LinearLayout header = row();
        header.addView(text(title, 17, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) header.addView(textButton(action, run));
        view.addView(header, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    TextView textButton(String title, Runnable action) {
        TextView view = text(title, 14, blue, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(40));
        view.setMinWidth(dp(48));
        view.setPadding(dp(12), 0, dp(12), 0);
        background(view, card, 20);
        view.setOnClickListener(v -> action.run());
        view.setContentDescription(title);
        pressFeedback(view);
        return view;
    }

    /** 48 dp pill button with the icon right next to its label. */
    LinearLayout button(String title, int icon, Runnable action, int style) {
        int fg = style == PRIMARY ? white : style == TONAL ? onPrimaryContainer : style == DANGER ? danger : blue;
        LinearLayout button = row();
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        if (style == OUTLINE || style == DANGER) {
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(blue & 0x29ffffff),
                    outlined(card, style == DANGER ? danger : border, 24), shape(0xff000000, 24)));
        } else {
            background(button, style == PRIMARY ? buttonBlue : primaryContainer, 24);
        }
        if (icon != 0) {
            ImageView image = icon(icon, fg, null);
            button.addView(image, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        TextView label = text(title, 15, fg, true);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(icon != 0 ? dp(8) : 0, 0, 0, 0);
        button.addView(label);
        button.setOnClickListener(v -> action.run());
        button.setContentDescription(title);
        pressFeedback(button);
        return button;
    }

    /** Lays buttons side by side with equal widths and an 8 dp gap. */
    LinearLayout buttons(View... views) {
        LinearLayout line = row();
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) params.leftMargin = dp(8);
            line.addView(views[i], params);
        }
        return line;
    }

    ImageView iconButton(int resource, String description, Runnable action) {
        ImageView view = icon(resource, muted, description);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        background(view, 0, 24);
        view.setOnClickListener(v -> action.run());
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        pressFeedback(view);
        return view;
    }

    /** Small rounded label such as "Paired" or "Online". */
    TextView chip(String value, int fg, int bg) {
        TextView view = text(value, 12, fg, true);
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
        view.setBackground(shape(bg, 12));
        return view;
    }

    /** A list row: leading badge, title + subtitle, optional trailing view. */
    LinearLayout listRow(View leading, String title, CharSequence subtitle, View trailing, Runnable click) {
        LinearLayout item = row();
        item.setMinimumHeight(dp(64));
        item.setPadding(dp(12), dp(10), dp(8), dp(10));
        if (click != null) {
            background(item, surfaceAlt, 16);
            item.setOnClickListener(v -> click.run());
            pressFeedback(item);
        } else {
            item.setBackground(shape(surfaceAlt, 16));
        }
        if (leading != null) item.addView(leading);
        LinearLayout labels = column();
        labels.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(title, 15, ink, true);
        singleLine(name);
        labels.addView(name);
        if (subtitle != null) {
            TextView sub = text("", 13, muted, false);
            sub.setText(subtitle);
            sub.setMaxLines(3);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(2);
            labels.addView(sub, params);
        }
        item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) item.addView(trailing);
        return item;
    }

    /** "● Online" style status line. */
    LinearLayout status(String value, int color) {
        LinearLayout line = row();
        line.addView(dot(color), new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView label = text(value, 13, color, false);
        label.setPadding(dp(6), 0, 0, 0);
        line.addView(label);
        return line;
    }

    void empty(LinearLayout parent, int resource, String title, String detail) {
        LinearLayout body = column();
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(8), dp(20), dp(8), dp(20));
        body.addView(badge(resource, blue, primaryContainer, 56, true));
        TextView heading = text(title, 16, ink, true);
        heading.setGravity(Gravity.CENTER);
        add(body, heading, 12);
        TextView copy = text(detail, 14, muted, false);
        copy.setGravity(Gravity.CENTER);
        add(body, copy, 4);
        add(parent, body, 4);
    }

    /** Segmented choice (e.g. System / Light / Dark). */
    LinearLayout segmented(String[] options, int selected, java.util.function.IntConsumer choose) {
        LinearLayout group = row();
        group.setPadding(dp(4), dp(4), dp(4), dp(4));
        group.setBackground(shape(surfaceAlt, 24));
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView option = text(options[i], 14, i == selected ? white : ink, true);
            option.setGravity(Gravity.CENTER);
            option.setMinHeight(dp(40));
            if (i == selected) background(option, buttonBlue, 20);
            else background(option, surfaceAlt, 20);
            option.setContentDescription(options[i] + (i == selected ? ", selected" : ""));
            option.setOnClickListener(v -> choose.accept(index));
            group.addView(option, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return group;
    }

    View divider() {
        View line = new View(activity);
        line.setBackgroundColor(border);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        return line;
    }

    void singleLine(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    }

    Bar bar(int color) {
        Bar bar = new Bar(activity, color, border);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(6)));
        return bar;
    }

    // ---- Animations (respect system setting: skip if disabled) ----

    static boolean animationsEnabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    void fadeIn(View view) {
        if (!animationsEnabled()) {
            view.setAlpha(1f);
            return;
        }
        view.setAlpha(0f);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(200);
        animator.addUpdateListener(a -> view.setAlpha((float) a.getAnimatedValue()));
        animator.start();
    }

    void slideInUp(View view) {
        if (!animationsEnabled()) {
            view.setAlpha(1f);
            view.setTranslationY(0);
            return;
        }
        int offset = dp(12);
        view.setAlpha(0f);
        view.setTranslationY(offset);
        ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(200);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float fraction = (float) a.getAnimatedValue();
            view.setAlpha(1f - fraction);
            view.setTranslationY(offset * fraction);
        });
        animator.start();
    }

    void slideOutDown(View view, Runnable onEnd) {
        if (!animationsEnabled()) {
            if (onEnd != null) onEnd.run();
            return;
        }
        int offset = dp(12);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(150);
        animator.setInterpolator(new android.view.animation.AccelerateInterpolator());
        animator.addUpdateListener(a -> {
            float fraction = (float) a.getAnimatedValue();
            view.setAlpha(1f - fraction);
            view.setTranslationY(offset * fraction);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (onEnd != null) onEnd.run();
            }
        });
        animator.start();
    }

    void pop(View view) {
        if (!animationsEnabled()) {
            view.setScaleX(1f);
            view.setScaleY(1f);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f, 2f);
        animator.setDuration(300);
        animator.addUpdateListener(a -> {
            float fraction = (float) a.getAnimatedValue();
            float scale = fraction <= 1f ? 0.8f + 0.25f * fraction : 1.05f - 0.05f * (fraction - 1f);
            view.setScaleX(scale);
            view.setScaleY(scale);
        });
        animator.start();
    }

    void pressFeedback(View view) {
        if (!animationsEnabled()) return;
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.setScaleX(0.97f);
                v.setScaleY(0.97f);
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                ValueAnimator animator = ValueAnimator.ofFloat(0.97f, 1f);
                animator.setDuration(100);
                animator.addUpdateListener(a -> {
                    float scale = (float) a.getAnimatedValue();
                    v.setScaleX(scale);
                    v.setScaleY(scale);
                });
                animator.start();
            }
            return false;
        });
    }

    /** Slide content in from below with crossfade. */
    void slideInContent(View view) {
        if (!animationsEnabled()) {
            view.setAlpha(1f);
            view.setTranslationY(0);
            return;
        }
        int offset = dp(12);
        view.setAlpha(0f);
        view.setTranslationY(offset);
        ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(200);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float fraction = (float) a.getAnimatedValue();
            view.setAlpha(1f - fraction);
            view.setTranslationY(offset * fraction);
        });
        animator.start();
    }

    // ----

    /** Rounded progress bar; a fraction below zero shows a moving "working" segment. */
    static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float fraction = -1;

        Bar(Context context, int color, int trackColor) {
            super(context);
            fill.setColor(color);
            track.setColor(trackColor);
        }

        /** A bar is 6 dp tall unless its layout gives it an exact height. */
        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int height = Math.round(6 * getResources().getDisplayMetrics().density);
            int mode = MeasureSpec.getMode(heightSpec);
            setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                    mode == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : height);
        }

        void set(float value, int color) {
            if (glide != null) glide.cancel();
            fraction = value;
            fill.setColor(color);
            invalidate();
        }

        private ValueAnimator glide;

        /** Moves smoothly to {@code value} (at most 250 ms behind); jumps when animations are off or the bar is or was "working". */
        void glide(float value, int color) {
            if (value < 0 || fraction < 0 || !animationsEnabled() || !isAttachedToWindow()) {
                set(value, color);
                return;
            }
            if (glide != null) glide.cancel();
            fill.setColor(color);
            glide = ValueAnimator.ofFloat(fraction, value);
            glide.setDuration(250);
            glide.setInterpolator(new android.view.animation.DecelerateInterpolator());
            glide.addUpdateListener(a -> {
                fraction = (float) a.getAnimatedValue();
                invalidate();
            });
            glide.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (glide != null) glide.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float w = getWidth();
            float r = h / 2;
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, track);
            if (fraction >= 0) {
                rect.set(0, 0, Math.max(h, w * Math.min(1, fraction)), h);
            } else {
                float t = (android.os.SystemClock.uptimeMillis() % 1400) / 1400f;
                float start = (w * 1.3f) * t - w * 0.3f;
                rect.set(Math.max(0, start), 0, Math.min(w, start + w * 0.3f), h);
                postInvalidateOnAnimation();
            }
            canvas.drawRoundRect(rect, r, r, fill);
        }
    }
}
