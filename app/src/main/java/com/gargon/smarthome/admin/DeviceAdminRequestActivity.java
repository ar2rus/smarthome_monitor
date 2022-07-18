package com.gargon.smarthome.admin;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;

import com.gargon.smarthome.MainService;
import com.gargon.smarthome.R;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;


public class DeviceAdminRequestActivity extends Activity {

    public static final int REQUEST_ENABLED = 1;

    private ComponentName adminComponent;

    private DevicePolicyManager devicePolicyManager;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_admin);

        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, DeviceAdminReceiver_.class);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            startActivityForResult(intent, REQUEST_ENABLED);
        } else {
            startActivityForResult(getIntent(), REQUEST_ENABLED);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLED) {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                Intent serviceLauncher = new Intent(getApplicationContext(), MainService.class);
                getApplicationContext().startService(serviceLauncher);
            }
            setResult(Activity.RESULT_OK);
            finish();
        }
    }

}
