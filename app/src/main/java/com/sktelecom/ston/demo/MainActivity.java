package com.sktelecom.ston.demo;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.widget.EditText;

import com.evernym.sdk.vcx.LibVcx;
import com.evernym.sdk.vcx.VcxException;
import com.evernym.sdk.vcx.connection.ConnectionApi;
import com.evernym.sdk.vcx.credential.CredentialApi;
import com.evernym.sdk.vcx.proof.DisclosedProofApi;
import com.evernym.sdk.vcx.utils.UtilsApi;
import com.evernym.sdk.vcx.vcx.VcxApi;
import com.evernym.sdk.vcx.wallet.WalletApi;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.sktelecom.ston.demo.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VCX";
    private static final String CONFIG = "provision_config";

    SharedPreferences sharedPref;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Control logs from libvcx (Make log level to trace if you want to see all logs from Rust)
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "ERROR");

        try {
            Os.setenv("EXTERNAL_STORAGE", getExternalFilesDir(null).getAbsolutePath(), true);
        } catch (ErrnoException e) {
            e.printStackTrace();
        }

        if (!LibVcx.isInitialized()) {
            LibVcx.init();
        }

        sharedPref = getPreferences(Context.MODE_PRIVATE);

        //If provisioned, just init vcx with saved configuration
        if (sharedPref.contains(CONFIG)) {
            String config = sharedPref.getString(CONFIG, "");

            //Initialize libvcx with configuration
            try {
                int state = VcxApi.vcxInitWithConfig(config).get();
                Log.d(TAG, "Init with config: " + VcxApi.vcxErrorCMessage(state));
            } catch (VcxException | InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

    }

    public void onProvisionClicked (View v) {

        //Provision an agent and wallet, get back configuration details
        InputStream inputStreamConfig = getResources().openRawResource(R.raw.provision_config);

        String provisionConfig = null;

        try {
            provisionConfig = convertStreamToString(inputStreamConfig);
        } catch (Exception e) {
            e.printStackTrace();
        }

        String config = UtilsApi.vcxProvisionAgent(provisionConfig);
        Log.d(TAG, "Config: " + prettyJson(config));

        InputStream inputStreamGenesis = getResources().openRawResource(R.raw.genesis_txn);

        File poolConfig = null;

        try {
            byte[] buffer = new byte[inputStreamGenesis.available()];
            inputStreamGenesis.read(buffer);
            poolConfig = File.createTempFile("pool_config", ".txn", this.getBaseContext().getCacheDir());
            Files.write(buffer, poolConfig);
        } catch (IOException e) {
            e.printStackTrace();
        }

        //Set some additional configuration options specific to alice
        DocumentContext ctx = JsonPath.parse(config);
        ctx.set("$.institution_name", "alice_institute");
        ctx.set("$.institution_logo_url", "http://robohash.org/234");
        ctx.set("$.genesis_path", poolConfig.getAbsolutePath());
        Log.d(TAG, "New config: " + prettyJson(ctx.jsonString()));

        //Initialize libvcx with new configuration
        try {
            int state = VcxApi.vcxInitWithConfig(ctx.jsonString()).get();
            Log.d(TAG, "Init with config: " + VcxApi.vcxErrorCMessage(state));
        } catch (VcxException | InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }

        //Save configuration details in the shared preference
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString(CONFIG, ctx.jsonString());
        editor.commit();

    }

    public void onConnectionClicked (View v) {
        //Get invitation details from the text box
        EditText invitationEditText = (EditText) findViewById(R.id.invitation);
        String invitation = invitationEditText.getText().toString();

        try {
            //Create a connection to faber
            int connectionHandle = ConnectionApi.vcxCreateConnectionWithInvite("alice", invitation).get();

            String connectionDetails = ConnectionApi.vcxConnectionConnect(connectionHandle, "{\"use_public_did\":true}").get();
            Log.d(TAG, "Connection details: " + prettyJson(connectionDetails));

            String connection = ConnectionApi.connectionSerialize(connectionHandle).get();
            Log.d(TAG, "Serialized connection: " + prettyJson(connection));

            String pwDid = ConnectionApi.connectionGetPwDid(connectionHandle).get();

            WalletApi.addRecordWallet("connection", pwDid, connection).get();

            ConnectionApi.connectionRelease(connectionHandle);
        } catch (VcxException | ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void onUpdateClicked (View v) {
        String messages = null;
        try {
            messages = UtilsApi.vcxGetMessages("MS-103", null, null).get();
            Log.d(TAG, "Messages: " + messages);

            String pwDid = JsonPath.read(messages,"$.[0].pairwiseDID");
            String connectionRecord = WalletApi.getRecordWallet("connection", pwDid, "").get();
            String connection = JsonPath.read(connectionRecord,"$.value");

            LinkedHashMap<String, Object> message = JsonPath.read(messages,"$.[0].msgs[0]");

            String decryptedPayload = (String)message.get("decryptedPayload");
            String uid = (String)message.get("uid");
            Log.d(TAG, "Decrypted payload: " + decryptedPayload + ", UID: " + uid);

            String payloadMessage = JsonPath.read(decryptedPayload,"$.@msg");
            Log.d(TAG, "Payload message: " + payloadMessage);

            String type = JsonPath.read(decryptedPayload,"$.@type.name");
            Log.d(TAG, "Type: " + type);

            switch(type) {
                //connection response or ack of proof request
                case "aries":
                    String innerType = JsonPath.read(payloadMessage,"$.@type");

                    //connection response
                    if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response")){
                        int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();
                        int state = ConnectionApi.vcxConnectionUpdateStateWithMessage(connectionHandle, JsonPath.parse(message).jsonString()).get();

                        if (state == 4) {
                            connection = ConnectionApi.connectionSerialize(connectionHandle).get();
                            Log.d(TAG, "Serialized connection: " + prettyJson(connection));
                            WalletApi.updateRecordWallet("connection", pwDid, connection).get();
                            ConnectionApi.connectionRelease(connectionHandle);
                        }
                    }
                    //ack of proof request
                    else if (innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/ack")){
                        String threadId = JsonPath.read(payloadMessage,"$.~thread.thid");
                        String proofRecord = WalletApi.getRecordWallet("proof", threadId, "").get();
                        String proof = JsonPath.read(proofRecord,"$.value");

                        int proofHandle = DisclosedProofApi.proofDeserialize(proof).get();
                        //For now, Java wrapper doesn't implement proofUpdateStateWithMessage API, and it is fixed in https://github.com/hyperledger/indy-sdk/pull/2156
                        //int state = DisclosedProofApi.proofUpdateStateWithMessage(proofHandle, JsonPath.parse(message).jsonString()).get();
                        int state = DisclosedProofApi.proofUpdateState(proofHandle).get();

                        if (state == 4) {
                            Log.d(TAG, "Proof is OK");
                        }

                        DisclosedProofApi.proofRelease(proofHandle);
                    }
                    break;
                case "credential-offer":
                    handleCredentialOffer(connection, payloadMessage, pwDid, uid);
                    break;
                case "credential":
                    handleCredential(payloadMessage);
                    break;
                case "presentation-request":
                    handlePresentationRequest(connection, payloadMessage, pwDid, uid);
                    break;
                default:

            }

        } catch (VcxException | ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }

    }

    private void handleCredentialOffer(String connection, String payloadMessage, String pwDid, String uid) throws VcxException, ExecutionException, InterruptedException {
        int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

        //Create a credential object from the credential offer
        List<String> credentialOffer = JsonPath.read(payloadMessage,"$");
        String offer = JsonPath.parse(credentialOffer).jsonString();
        Log.d(TAG, "Offer: " + prettyJson(offer));
        int credentialHandle = CredentialApi.credentialCreateWithOffer("1", offer).get();

        //Send credential request
        CredentialApi.credentialSendRequest(credentialHandle, connectionHandle, 0).get();

        //Update agency message state
        String msgJson = "[{\"pairwiseDID\":\"" + pwDid + "\",\"uids\":[\"" + uid + "\"]}]";
        UtilsApi.vcxUpdateMessages("MS-106", msgJson);

        //Serialize the object
        String credential = CredentialApi.credentialSerialize(credentialHandle).get();
        Log.d(TAG, "Serialized credential: " + prettyJson(credential));

        //Persist the object in the wallet
        String threadId = JsonPath.read(credential,"$.data.holder_sm.thread_id");
        WalletApi.addRecordWallet("credential", threadId, credential).get();

        CredentialApi.credentialRelease(credentialHandle);

        /* For now, LibVCX has a bug, so you need to maintain the connection without releasing it
           in order to get a credential in the next step */
        //ConnectionApi.connectionRelease(connectionHandle);
    }

    private void handleCredential(String payloadMessage) throws VcxException, ExecutionException, InterruptedException {
        String claimOfferId = JsonPath.read(payloadMessage,"$.claim_offer_id");
        String credentialRecord = WalletApi.getRecordWallet("credential", claimOfferId, "").get();
        String credential = JsonPath.read(credentialRecord,"$.value");

        int credentialHandle = CredentialApi.credentialDeserialize(credential).get();
        Log.d(TAG, "credentialHandle: " + credentialHandle);

        //For now, credentialUpdateStateWithMessage has a bug... use credentialUpdateState instead
        //int state = CredentialApi.credentialUpdateStateWithMessage(credentialHandle, message).get();
        int state = CredentialApi.credentialUpdateState(credentialHandle).get();

        if (state == 4){
            credential = CredentialApi.credentialSerialize(credentialHandle).get();
            Log.d(TAG, "Serialized credential: " + prettyJson(credential));

        }

        CredentialApi.credentialRelease(credentialHandle);
    }

    private void handlePresentationRequest(String connection, String payloadMessage, String pwDid, String uid) throws VcxException, ExecutionException, InterruptedException {
        int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

        //Create a Disclosed proof object from proof request
        LinkedHashMap<String, Object> request = JsonPath.read(payloadMessage,"$");

        int proofHandle = DisclosedProofApi.proofCreateWithRequest("1", JsonPath.parse(request).jsonString()).get();

        //Query for credentials in the wallet that satisfy the proof request
        String credentials = DisclosedProofApi.proofRetrieveCredentials(proofHandle).get();

        //Use the first available credentials to satisfy the proof request
        DocumentContext ctx = JsonPath.parse(credentials);
        LinkedHashMap<String, Object> attrs = ctx.read("$.attrs");
        for(String key : attrs.keySet()){
            LinkedHashMap<String, Object> attr = JsonPath.read(attrs.get(key),"$.[0]");
            ctx.set("$.attrs." + key, JsonPath.parse("{\"credential\":null}").json());
            ctx.set("$.attrs." + key + ".credential", attr);
        }

        //Generate and send the proof
        DisclosedProofApi.proofGenerate(proofHandle, ctx.jsonString(), "{}").get();
        DisclosedProofApi.proofSend(proofHandle, connectionHandle).get();

        //Update agency message state
        String msgJson = "[{\"pairwiseDID\":\"" + pwDid + "\",\"uids\":[\"" + uid + "\"]}]";
        UtilsApi.vcxUpdateMessages("MS-106", msgJson);

        //Serialize the object
        String proof = DisclosedProofApi.proofSerialize(proofHandle).get();
        Log.d(TAG, "Serialized proof: " + prettyJson(proof));

        //Persist the object in the wallet
        String threadId = JsonPath.read(proof,"$.data.prover_sm.thread_id");
        WalletApi.addRecordWallet("proof", threadId, proof).get();

        DisclosedProofApi.proofRelease(proofHandle);
        /* For now, LibVCX has a bug, so you need to maintain the connection without releasing it
           in order to get an ack from Faber in the next step */
        //ConnectionApi.connectionRelease(connectionHandle);
    }

    private String convertStreamToString(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    private static String prettyJson(String jsonString) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(JsonParser.parseString(jsonString));
    }
}
