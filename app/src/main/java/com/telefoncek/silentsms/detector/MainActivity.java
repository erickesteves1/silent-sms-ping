package com.telefoncek.silentsms.detector;

import android.Manifest;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.mobicents.protocols.ss7.map.api.smstpdu.SmsStatusReportTpdu;
import org.mobicents.protocols.ss7.map.api.smstpdu.SmsTpdu;
import org.mobicents.protocols.ss7.map.api.smstpdu.SmsTpduType;
import org.mobicents.protocols.ss7.map.api.smstpdu.Status;
import org.mobicents.protocols.ss7.map.smstpdu.SmsTpduImpl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MainActivity extends AppCompatActivity {
    ///WidgetsFlutterBinding.ensureInitialized();
    final byte[] payload = new byte[]{0x0A, 0x06, 0x03, (byte) 0xB0, (byte) 0xAF, (byte) 0x82, 0x03, 0x06, 0x6A, 0x00, 0x05};
    byte[] lastSendResultPDU = new byte[0];

    final String TAG = "Ping SMS";

    final String SENT = "pingsms.sent";
    final String DELIVER = "pingsms.deliver";

    final IntentFilter sentFilter = new IntentFilter(SENT);
    final IntentFilter deliveryFilter = new IntentFilter(DELIVER);
    final IntentFilter wapDeliveryFilter = new IntentFilter("android.provider.Telephony.WAP_PUSH_DELIVER");

    MenuItem pickContact;
    public final static int MENU_ITEM_PICK_CONTACT = 999;
    MenuItem clearHistory;
    //public final static int MENU_ITEM_CLEAR_HISTORY = 998;
    MenuItem receiveDataSms;
    //public final static int MENU_ITEM_RECEIVE_DATA_SMS = 997;
    MenuItem receivedStorage;
    public final static int MENU_ITEM_RECEIVED_STORAGE = 996;

    SharedPreferences preferences;
    public final static String PREF_LAST_NUMBER = "pref_last_number";
    public final static String PREF_HISTORY = "pref_history";
    //public final static String PREF_RECEIVE_DATA_SMS = "pref_receive_data_sms";
    public final static String PREF_DATA_SMS_STORE = "pref_data_sms_store";

    ArrayAdapter<String> historyAdapter;
    ArrayList<String> historyContent = new ArrayList<>();

    PendingIntent sentPI;
    PendingIntent deliveryPI;

    // Always resolve a ping to Online/Offline. Many carriers never return a delivery
    // report for data SMS, so without this the result would stay blank forever. If no
    // positive report arrives within this timeout, the target is treated as offline.
    static final long PING_TIMEOUT_MS = 30_000L;
    final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    Runnable pingTimeout;
    boolean awaitingResult = false;

    EditText phoneNumber;
    TextView statusText, resultText;
    ListView historyList;
    ImageButton resultPduDetails;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Use an in-layout Toolbar as the action bar (theme is NoActionBar) so nothing
        // from the system decor overlaps our content under the enforced edge-to-edge on
        // Android 15+/16. We pad our own root for all system bars + display cutouts; the
        // Toolbar, as the first child, then sits just below the status bar.
        setSupportActionBar(findViewById(R.id.toolbar));
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootMain), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });

        phoneNumber = findViewById(R.id.phoneNumber);
        statusText = findViewById(R.id.sendStatus);
        resultText = findViewById(R.id.resultStatus);
        historyList = findViewById(R.id.historyList);
        resultPduDetails = findViewById(R.id.resultPduDetails);

        // If no delivery report comes back within the timeout, declare the target offline
        // so the user always gets a definite Online/Offline answer.
        pingTimeout = () -> {
            if (awaitingResult) {
                awaitingResult = false;
                resultText.setText(R.string.offline);
            }
        };

        preferences = getPreferences(Context.MODE_PRIVATE);
        phoneNumber.setText(preferences.getString(PREF_LAST_NUMBER, getString(R.string.phonenumber)));

        findViewById(R.id.sendButton).setOnClickListener(v -> {
            final String phoneNum = phoneNumber.getText().toString();
            if (MainActivity.this.checkPermissions() && !TextUtils.isEmpty(phoneNum) && Patterns.PHONE.matcher(phoneNum).matches()) {
                updateHistory(phoneNum);
                try {
                    getSystemService(SmsManager.class).sendDataMessage(phoneNum, null, (short) 9200, payload, sentPI, deliveryPI);
                    statusText.setText(null);
                    resultText.setText(R.string.waiting);
                    awaitingResult = true;
                    timeoutHandler.removeCallbacks(pingTimeout);
                    timeoutHandler.postDelayed(pingTimeout, PING_TIMEOUT_MS);
                } catch (Exception e) {
                    // e.g. SmsManager unavailable, no SEND_SMS, radio off
                    Log.e(TAG, "sendDataMessage failed", e);
                    awaitingResult = false;
                    statusText.setText(R.string.notsent);
                    resultText.setText(R.string.offline);
                }
            }
        });

        resultPduDetails.setOnClickListener(v -> showPduInfoDialog());

        sentPI = PendingIntent.getBroadcast(this, 0x1337, new Intent(SENT).setPackage(getPackageName()), PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_MUTABLE);
        deliveryPI = PendingIntent.getBroadcast(this, 0x1337, new Intent(DELIVER).setPackage(getPackageName()), PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_MUTABLE);

        historyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_2, android.R.id.text1);
        historyList.setAdapter(historyAdapter);
        updateHistory(null);

        historyList.setOnItemClickListener((parent, view, position, id) -> phoneNumber.setText(historyAdapter.getItem(position)));
    }

    void updateHistory(String current) {
        if (current != null) {
            preferences.edit().putString(PREF_LAST_NUMBER, current).apply();
            preferences.edit().putString(PREF_HISTORY, preferences.getString(PREF_HISTORY, "").concat(current + ",")).apply();
        }

        historyContent.clear();
        historyContent.addAll(Arrays.asList(preferences.getString(PREF_HISTORY, "").split(",")));

        if (historyAdapter != null) {
            historyAdapter.clear();
            historyAdapter.addAll(historyContent);
            historyAdapter.notifyDataSetChanged();
        }
    }

    void clearHistory() {
        preferences.edit().putString(PREF_HISTORY, "").apply();
        updateHistory(null);
    }

    boolean checkPermissions() {
        List<String> missingPermissions = new ArrayList<>();

        int sendSmsPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS);
        int readPhonePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE);
        int receiveSmsPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS);
        int postNotificationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS);


        if (sendSmsPermission != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.SEND_SMS);
        }

        if (readPhonePermission != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.READ_PHONE_STATE);
        }

        if (receiveSmsPermission != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.RECEIVE_SMS);
        }

        if (postNotificationPermission != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }


        if (!missingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toArray(new String[0]), 1);
            return false;
        }

        return true;
    }

    String getLogBytesHex(byte[] array) {
        StringBuilder sb = new StringBuilder();
        for (byte b : array) {
            sb.append(String.format("0x%02X ", b));
        }
        return sb.toString();
    }

    @Override
    protected void onStart() {
        super.onStart();
        String ns = Context.NOTIFICATION_SERVICE;
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // API 34+ requires an explicit export flag for context-registered receivers.
        // These broadcasts are internal to the app, so they must not be exported.
        ContextCompat.registerReceiver(this, br, sentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, br, deliveryFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, br, wapDeliveryFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        String ns = Context.NOTIFICATION_SERVICE;
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(br);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timeoutHandler.removeCallbacks(pingTimeout);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        pickContact = menu.findItem(MENU_ITEM_PICK_CONTACT);
        if (pickContact == null) {
            pickContact = menu.add(Menu.NONE, MENU_ITEM_PICK_CONTACT, Menu.NONE, R.string.pick_contact)
                    .setIcon(R.drawable.ic_menu_invite).setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        receivedStorage = menu.findItem(MENU_ITEM_RECEIVED_STORAGE);
        if (receivedStorage == null) {
            receivedStorage = menu.add(Menu.NONE, MENU_ITEM_RECEIVED_STORAGE, Menu.NONE, "Data Messages Storage")
                    .setIcon(R.drawable.silent_sms_ghost).setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ITEM_PICK_CONTACT:
                pickContact();
                return true;
            case MENU_ITEM_RECEIVED_STORAGE:
                startActivity(new Intent(this, StoreActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    void pickContact() {
        Intent pickIntent = new Intent(Intent.ACTION_PICK);
        pickIntent.setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE);
        ActivityCompat.startActivityForResult(this, pickIntent, MENU_ITEM_PICK_CONTACT, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            if (requestCode == MENU_ITEM_PICK_CONTACT && resultCode == RESULT_OK) {
                Uri contactUri = data.getData();
                Cursor cursor = null;

                if (contactUri != null) {
                    String[] projection = new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER};
                    cursor = getContentResolver().query(contactUri, projection,
                            null, null, null);
                }

                if (cursor != null && cursor.moveToFirst()) {
                    int numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                    phoneNumber.setText(cursor.getString(numberIndex));
                }

                if (cursor != null) {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onActivityResult failed", e);
            Toast.makeText(this, R.string.pick_contact_failed, Toast.LENGTH_SHORT).show();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    public void showPduInfoDialog() {
        if (lastSendResultPDU == null || lastSendResultPDU.length == 0) {
            resultPduDetails.setVisibility(View.INVISIBLE);
            return;
        }

        SmsTpdu parsedTpdu = getSmsTpdu(lastSendResultPDU);

        AlertDialog dialog = (new AlertDialog.Builder(this))
                .setTitle("Result PDU details")
                .setMessage(parsedTpdu != null ? parsedTpdu.toString() : "N/A")
                .setCancelable(true)
                .setNeutralButton("Close", (dialog1, which) -> dialog1.dismiss())
                .create();

        dialog.show();
    }

    @Nullable
    public SmsTpdu getSmsTpdu(byte[] pduBytes) {
        SmsTpdu result = null;
        try {
            result = SmsTpduImpl.createInstance(pduBytes, false, null);
        } catch (Exception e) {
            Log.d(TAG, "getSmsTpdu:1", e);
        }
        if (result == null) {
            try {
                byte[] pduWithoutSCA = Arrays.copyOfRange(pduBytes, pduBytes[0] + 1, pduBytes.length);
                result = SmsTpduImpl.createInstance(pduWithoutSCA, false, null);
            } catch (Exception e) {
                Log.d(TAG, "getSmsTpdu:2", e);
            }
        }
        return result;
    }

    BroadcastReceiver br = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.e(TAG, "intent: " + ((intent == null || intent.getAction() == null) ? "null" : intent.getAction()));
            Log.e(TAG, "result: " + getResultCode());
            Log.e(TAG, "pdu (if any): " + ((intent != null && intent.hasExtra("pdu")) ? getLogBytesHex(intent.getByteArrayExtra("pdu")) : ""));

            if (intent == null) {
                return;
            }

            if (SENT.equalsIgnoreCase(intent.getAction())) {
                if (getResultCode() == RESULT_OK) {
                    // Sent OK; keep waiting for the delivery report (or the timeout).
                    statusText.setText(R.string.sent);
                } else {
                    // Could not even send it -> resolve to offline immediately.
                    statusText.setText(R.string.notsent);
                    awaitingResult = false;
                    timeoutHandler.removeCallbacks(pingTimeout);
                    resultText.setText(R.string.offline);
                }
            } else if (DELIVER.equalsIgnoreCase(intent.getAction())) {
                boolean delivered = false;
                if (intent.hasExtra("pdu")) {
                    byte[] pdu = intent.getByteArrayExtra("pdu");
                    if (pdu != null && pdu.length > 1) {
                        lastSendResultPDU = pdu;
                        resultPduDetails.setVisibility(View.VISIBLE);
                        SmsTpdu parsedResultPdu = getSmsTpdu(pdu);
                        if (parsedResultPdu != null) {
                            Log.d(TAG, parsedResultPdu.toString());
                            delivered = parsedResultPdu.getSmsTpduType().equals(SmsTpduType.SMS_STATUS_REPORT) && ((SmsStatusReportTpdu) parsedResultPdu).getStatus().getCode() == Status.SMS_RECEIVED;
                        } else {
                            String resultPdu = getLogBytesHex(pdu).trim();
                            delivered = "00".equalsIgnoreCase(resultPdu.substring(resultPdu.length() - 2));
                        }
                    }
                }
                awaitingResult = false;
                timeoutHandler.removeCallbacks(pingTimeout);
                resultText.setText(delivered ? R.string.delivered : R.string.offline);
            }

        }
    };
}
