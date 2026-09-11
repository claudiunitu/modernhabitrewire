package com.example.voward;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * The one explanation surface, shared by every setting on a screen.
 *
 * <p>Section 7.1: every setting gets an info affordance, including the read-only status rows.
 * That is thirty-odd explanations, so there is one sheet and the content is bound when it is
 * shown — inflating a hidden view per setting would be thirty layouts nobody is looking at.</p>
 *
 * <p>Each body leads with the consequence rather than the definition. A setting that is locked
 * while protection is active, or that cannot be undone without the recovery key, or that closes
 * an escape route, says so in its first sentence.</p>
 */
final class InfoSheet {

    private final BottomSheetDialog dialog;
    private final TextView title;
    private final TextView body;

    InfoSheet(Activity activity) {
        dialog = new BottomSheetDialog(activity);
        View content = activity.getLayoutInflater().inflate(R.layout.sheet_info, null);
        dialog.setContentView(content);
        title = content.findViewById(R.id.infoSheetTitle);
        body = content.findViewById(R.id.infoSheetBody);
    }

    void show(int titleRes, int bodyRes) {
        title.setText(titleRes);
        body.setText(bodyRes);
        dialog.show();
    }

    /** Closed when the screen goes away, or the window leaks with the activity behind it. */
    void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }
}
