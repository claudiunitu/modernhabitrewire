package com.example.voward;

import android.app.Activity;
import android.view.View;
import android.widget.ImageView;

/**
 * The "add a rule" card on both rules screens, collapsed to its header until it is wanted.
 *
 * <p>Expanded it is most of a phone screen, and it is docked below the list, so leaving it open
 * left room for a single rule. Collapsed it costs one row and the list gets the rest.</p>
 *
 * <p>It starts open only when the list is empty, which is the one time there is nothing to
 * crowd and the composer is what the screen is for.</p>
 */
final class RuleComposer {

    private RuleComposer() {}

    /** Wires the header toggle on a screen that carries the composer card. */
    static void bind(Activity activity, boolean startExpanded) {
        View header = activity.findViewById(R.id.composerHeader);
        View body = activity.findViewById(R.id.composerBody);
        ImageView chevron = activity.findViewById(R.id.composerChevron);
        apply(body, chevron, startExpanded);
        header.setOnClickListener(view ->
                apply(body, chevron, body.getVisibility() != View.VISIBLE));
    }

    private static void apply(View body, ImageView chevron, boolean expanded) {
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        // The source glyph points right: a quarter turn down means closed, up means open.
        chevron.setRotation(expanded ? 270f : 90f);
    }
}
