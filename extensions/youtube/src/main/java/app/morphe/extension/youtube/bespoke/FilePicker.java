package app.morphe.extension.youtube.bespoke;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import app.morphe.extension.shared.Logger;

/**
 * Opens the system file picker and gets the chosen file back, without access to the
 * host activity's onActivityResult: a headless (platform) fragment starts the picker
 * and receives the result. No storage permission is needed.
 */
@SuppressWarnings({"deprecation", "unused"})
public final class FilePicker extends Fragment {

    public interface Callback {
        void onPicked(Uri uri);
    }

    private static final String TAG = "bespoke_feed_file_picker";
    private static final String ARG_INTENT = "intent";
    private static final int REQUEST_CODE = 0x4854; // "HT"

    /** The callback of the picker in progress. Lost if the app process is killed meanwhile. */
    private static Callback pending;

    /** Lets the user choose where to create a new file. */
    static void create(Activity activity, String mimeType, String fileName, Callback callback) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_TITLE, fileName);
        start(activity, intent, callback);
    }

    /** Lets the user choose an existing file. */
    static void open(Activity activity, Callback callback) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain",
                        "application/octet-stream"});
        start(activity, intent, callback);
    }

    private static void start(Activity activity, Intent intent, Callback callback) {
        pending = callback;
        FilePicker fragment = new FilePicker();
        Bundle args = new Bundle();
        args.putParcelable(ARG_INTENT, intent);
        fragment.setArguments(args);
        activity.getFragmentManager().beginTransaction().add(fragment, TAG).commitAllowingStateLoss();
    }

    public FilePicker() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return; // Picker already started before a re-creation.
        try {
            Intent intent = getArguments().getParcelable(ARG_INTENT);
            startActivityForResult(intent, REQUEST_CODE);
        } catch (Exception ex) {
            Logger.printException(() -> "Bespoke feed file picker could not open", ex);
            finish();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) return;
        Callback callback = pending;
        pending = null;
        finish();
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && callback != null) {
            try {
                callback.onPicked(data.getData());
            } catch (Exception ex) {
                Logger.printException(() -> "Bespoke feed file picker callback failed", ex);
            }
        }
    }

    private void finish() {
        try {
            getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        } catch (Exception ex) {
            Logger.printDebug(() -> "Bespoke feed file picker could not be removed");
        }
    }
}
