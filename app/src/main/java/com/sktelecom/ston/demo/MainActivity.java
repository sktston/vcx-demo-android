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
import com.google.common.primitives.UnsignedInts;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

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

        //Control logs from libvcx (Make a log level to TRACE if you want to see all logs from Rust)
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

            WalletApi.addRecordWallet("connection", pwDid, connection, "").get();

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
            int connectionHandle = ConnectionApi.connectionDeserialize(connection).get();

            LinkedHashMap<String, Object> message = JsonPath.read(messages,"$.[0].msgs[0]");

            String decryptedPayload = (String)message.get("decryptedPayload");
            String uid = (String)message.get("uid");

            String payloadMessage = JsonPath.read(decryptedPayload,"$.@msg");
            Log.d(TAG, "Payload message: " + payloadMessage);

            String type = JsonPath.read(decryptedPayload,"$.@type.name");
            Log.d(TAG, "Type: " + type);

            //msgJson is going to be used to update the message state in agency
            String msgJson = "[{\"pairwiseDID\":\"" + pwDid + "\",\"uids\":[\"" + uid + "\"]}]";

            switch(type) {
                //connection response or ack of proof request
                case "aries":
                    String innerType = JsonPath.read(payloadMessage,"$.@type");

                    //connection response
                    if(innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response")){
                        int state = ConnectionApi.vcxConnectionUpdateState(connectionHandle).get();

                        if (state == 4) {
                            connection = ConnectionApi.connectionSerialize(connectionHandle).get();
                            Log.d(TAG, "Serialized connection: " + prettyJson(connection));
                            WalletApi.updateRecordWallet("connection", pwDid, connection).get();
                        }
                    }
                    //ack of proof request
                    else if (innerType.equals("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/ack")){
                        String threadId = JsonPath.read(payloadMessage,"$.~thread.thid");
                        String proofRecord = WalletApi.getRecordWallet("proof", threadId, "").get();
                        String proof = JsonPath.read(proofRecord,"$.value");

                        //It replaces a connection handle in the proof object with a currently available one.
                        //There should be a better way to handle this issue.
                        proof = JsonPath.parse(proof)
                                .set("$.data.prover_sm.state.PresentationSent.connection_handle", UnsignedInts.toLong(connectionHandle))
                                .jsonString();

                        int proofHandle = DisclosedProofApi.proofDeserialize(proof).get();
                        int state = DisclosedProofApi.proofUpdateState(proofHandle).get();

                        if (state == 4) {
                            Log.d(TAG, "Proof is OK");
                        }

                        DisclosedProofApi.proofRelease(proofHandle);
                    }
                    break;
                case "credential-offer":
                    handleCredentialOffer(connectionHandle, JsonPath.read(payloadMessage,"$.@id"));
                    UtilsApi.vcxUpdateMessages("MS-106", msgJson).get();
                    break;
                case "credential":
                    handleCredential(connectionHandle, JsonPath.read(payloadMessage,"$.~thread.thid"));
                    break;
                case "presentation-request":
                    handlePresentationRequest(connectionHandle, JsonPath.read(payloadMessage,"$.@id"));
                    UtilsApi.vcxUpdateMessages("MS-106", msgJson).get();
                    break;
                default:

            }

            ConnectionApi.connectionRelease(connectionHandle);

        } catch (VcxException | ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }

    }

    private void handleCredentialOffer(int connectionHandle, String threadId) throws VcxException, ExecutionException, InterruptedException {
        //Check agency for a credential offer
        String offers = CredentialApi.credentialGetOffers(connectionHandle).get();

        //Create a credential object from the credential offer
        LinkedHashMap<String, Object> credentialOffer = JsonPath.read(offers,"$.[0]");
        int credentialHandle = CredentialApi.credentialCreateWithOffer("1", JsonPath.parse(credentialOffer).jsonString()).get();

        //Send credential request
        CredentialApi.credentialSendRequest(credentialHandle, connectionHandle, 0).get();

        //Serialize the object
        String credential = CredentialApi.credentialSerialize(credentialHandle).get();
        Log.d(TAG, "Serialized credential: " + prettyJson(credential));

        //Persist the object in the wallet
        WalletApi.addRecordWallet("credential", threadId, credential, "").get();

        CredentialApi.credentialRelease(credentialHandle);
    }

    private void handleCredential(int connectionHandle, String threadId) throws VcxException, ExecutionException, InterruptedException {
        String credentialRecord = WalletApi.getRecordWallet("credential", threadId, "").get();
        String credential = JsonPath.read(credentialRecord,"$.value");

        //It replaces a connection handle in the credential object with a currently available one.
        //There should be a better way to handle this issue.
        credential = JsonPath.parse(credential)
                .set("$.data.holder_sm.state.RequestSent.connection_handle", UnsignedInts.toLong(connectionHandle))
                .jsonString();

        int credentialHandle = CredentialApi.credentialDeserialize(credential).get();
        Log.d(TAG, "credentialHandle: " + credentialHandle);

        int state = CredentialApi.credentialUpdateState(credentialHandle).get();

        if (state == 4){
            credential = CredentialApi.credentialSerialize(credentialHandle).get();
            Log.d(TAG, "Serialized credential: " + prettyJson(credential));

        }

        CredentialApi.credentialRelease(credentialHandle);
    }

    private void handlePresentationRequest(int connectionHandle, String threadId) throws VcxException, ExecutionException, InterruptedException {
        //Check agency for a proof request
        String requests = DisclosedProofApi.proofGetRequests(connectionHandle).get();

        //Create a Disclosed proof object from proof request
        LinkedHashMap<String, Object> request = JsonPath.read(requests,"$.[0]");
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

        //Serialize the object
        String proof = DisclosedProofApi.proofSerialize(proofHandle).get();
        Log.d(TAG, "Serialized proof: " + prettyJson(proof));

        //Persist the object in the wallet
        WalletApi.addRecordWallet("proof", threadId, proof, "").get();

        DisclosedProofApi.proofRelease(proofHandle);
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
