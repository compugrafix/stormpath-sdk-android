package com.stormpath.sdk;

import com.squareup.moshi.Json;
import com.squareup.moshi.Moshi;
import com.stormpath.sdk.android.AndroidPlatform;
import com.stormpath.sdk.models.RegisterParams;
import com.stormpath.sdk.models.SessionTokens;
import com.stormpath.sdk.models.SocialProviderConfiguration;
import com.stormpath.sdk.models.SocialProvidersResponse;
import com.stormpath.sdk.models.StormpathError;
import com.stormpath.sdk.models.UserProfile;
import com.stormpath.sdk.utils.StringUtils;
import com.stormpath.sdk.BuildConfig;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.annotation.NonNull;

import java.io.IOException;
import java.io.Serializable;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * The type Api manager.
 */
public class ApiManager {

    private final Platform platform;

    private final OkHttpClient okHttpClient;

    private final Executor callbackExecutor;

    private final StormpathConfiguration config;

    private final PreferenceStore preferenceStore;

    private final Moshi moshi = new Moshi.Builder().build();

    private static String version;

    /**
     * Instantiates a new Api manager.
     *
     * @param config   the config
     * @param platform the platform
     */
    ApiManager(StormpathConfiguration config, Platform platform) {
        this.config = config;
        this.preferenceStore = platform.preferenceStore();
        this.callbackExecutor = platform.callbackExecutor();
        HttpLoggingInterceptor httpLoggingInterceptor = new HttpLoggingInterceptor(new HttpLoggingInterceptor.Logger() {
            @Override
            public void log(String message) {
                Stormpath.logger().d(message);
            }
        });
        httpLoggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        this.okHttpClient = new OkHttpClient.Builder()
                .dispatcher(new Dispatcher(platform.httpExecutorService()))
                .addNetworkInterceptor(httpLoggingInterceptor)
                .build();
        this.platform = platform;
    }

    /**
     * Login.
     *
     * @param username the username
     * @param password the password
     * @param callback the callback
     */
    void login(String username, String password, StormpathCallback<Void> callback) {

        RequestBody formBody = new FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("grant_type", "password")
                .build();

        Request request = new Request.Builder()
                .url(config.getBaseUrl() + Endpoints.OAUTH_TOKEN)
                .headers(buildStandardHeaders())
                .post(formBody)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                try {
                    SessionTokens sessionTokens = moshi.adapter(SessionTokens.class).fromJson(response.body().source());
                    if (StringUtils.isBlank(sessionTokens.getAccessToken())) {
                        failureCallback(new RuntimeException("access_token was not found in response. See debug logs for details."));
                        return;
                    }

                    if (StringUtils.isBlank(sessionTokens.getRefreshToken())) {
                        Stormpath.logger().e("There was no refresh_token in the login response!");
                    }

                    preferenceStore.setAccessToken(sessionTokens.getAccessToken());
                    preferenceStore.setRefreshToken(sessionTokens.getRefreshToken());
                    successCallback(null);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        });
    }

    /**
     * Register.
     *
     * @param registerParams the register params
     * @param callback       the callback
     */
    public void register(RegisterParams registerParams, StormpathCallback<Void> callback) {
        RequestBody body = RequestBody
                .create(MediaType.parse("application/json"), moshi.adapter(RegisterParams.class).toJson(registerParams));

        Request request = new Request.Builder()
                .url(config.getBaseUrl() + Endpoints.REGISTER)
                .headers(buildStandardHeaders())
                .post(body)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                try {
                    // TODO: what the hell, how do we know if registration is successful or not?
                    // Does this code just always return success if the API call goes through?
                    String sessionTokens[] = {"PLACEHOLDER"};
                    String accessToken = sessionTokens[0];
                    String refreshToken = sessionTokens[1];

                    if (StringUtils.isNotBlank(accessToken)) {
                        preferenceStore.setAccessToken(accessToken);

                        if (StringUtils.isNotBlank(refreshToken)) {
                            preferenceStore.setRefreshToken(refreshToken);
                        }
                    } else {
                        Stormpath.logger().i("There was no access_token in the register cookies, if you want to skip the login after "
                                + "registration, enable the autologin in your Stormpath server app.");
                    }
                    successCallback(null);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        });
    }

    /**
     * Refresh access token.
     *
     * @param callback the callback
     */
    public void refreshAccessToken(final StormpathCallback<Void> callback) {
        String refreshToken = preferenceStore.getRefreshToken();

        if (StringUtils.isBlank(refreshToken)) {
            callbackExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    callback.onFailure(new StormpathError(platform.unknownErrorMessage(), new IllegalStateException(
                            "refresh_token was not found, did you forget to login? See debug logs for details.")));
                }
            });
            return;
        }

        RequestBody formBody = new FormBody.Builder()
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build();

        Request request = new Request.Builder()
                .url(config.getBaseUrl() + Endpoints.OAUTH_TOKEN)
                .headers(buildStandardHeaders())
                .post(formBody)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                try {
                    SessionTokens sessionTokens = moshi.adapter(SessionTokens.class).fromJson(response.body().source());
                    if (StringUtils.isBlank(sessionTokens.getAccessToken())) {
                        failureCallback(new RuntimeException("access_token was not found in response. See debug logs for details."));
                        return;
                    }

                    if (StringUtils.isBlank(sessionTokens.getRefreshToken())) {
                        Stormpath.logger().e("There was no refresh_token in the login response!");
                    }

                    preferenceStore.setAccessToken(sessionTokens.getAccessToken());
                    preferenceStore.setRefreshToken(sessionTokens.getRefreshToken());
                    successCallback(null);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        });
    }

    /**
     * Gets user profile.
     *
     * @param callback the callback
     */
    public void getUserProfile(final StormpathCallback<UserProfile> callback) {
        String accessToken = preferenceStore.getAccessToken();

        if (StringUtils.isBlank(accessToken)) {
            callbackExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    callback.onFailure(new StormpathError(platform.unknownErrorMessage(),
                            new IllegalStateException("access_token was not found, did you forget to login? See debug logs for details.")));
                }
            });
            return;
        }

        Request request = new Request.Builder()
                .url(config.getBaseUrl() + Endpoints.ME)
                .headers(buildStandardHeaders(accessToken))
                .get()
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<UserProfile>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<UserProfile> callback) {
                try {
                    UserProfileResponse userProfileResponse = moshi.adapter(UserProfileResponse.class).fromJson(response.body().source());
                    successCallback(userProfileResponse.userProfile);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        });
    }

    /**
     * Reset password.
     *
     * @param email    the email
     * @param callback the callback
     */
    public void resetPassword(String email, StormpathCallback<Void> callback) {
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), "{\"email\":\"" + email + "\"}");

        Request request = new Request.Builder()
                .url(config.getBaseUrl() + Endpoints.FORGOT)
                .headers(buildStandardHeaders())
                .post(body)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                successCallback(null);
            }
        });
    }

    /**
     * Logout.
     */
    public void logout() {
        String refreshToken = preferenceStore.getRefreshToken();

        if (StringUtils.isBlank(refreshToken)) {
            return;
        }

        RequestBody body = new FormBody.Builder()
                .add("token", refreshToken)
                .build();

        Request request = new Request.Builder()
                .url(config.getBaseUrl() + Endpoints.OAUTH_REVOKE)
                .headers(buildStandardHeaders())
                .post(body)
                .build();

        preferenceStore.clearAccessToken();
        preferenceStore.clearRefreshToken();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                // great - the token was also deleted on the API side
            }

            @Override
            public void onFailure(Call call, IOException e) {
                // this shouldn't happen but if it does we can live with it
            }
        });
    }

    /**
     * Verify email.
     *
     * @param sptoken  the sptoken
     * @param callback the callback
     */
    // TODO: may not need this for now...
    public void verifyEmail(String sptoken, StormpathCallback<Void> callback) {
        HttpUrl url = HttpUrl.parse(config.getBaseUrl() + "/verify").newBuilder()
                .addQueryParameter("sptoken", sptoken)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .headers(buildStandardHeaders())
                .get()
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                successCallback(null);
            }
        });
    }

    /**
     * Resend verification email.
     *
     * @param email    the email
     * @param callback the callback
     */
    // TODO: may not need this for now...
    public void resendVerificationEmail(String email, StormpathCallback<Void> callback) {
        FormBody body = new FormBody.Builder()
                .add("login", email)
                .build();

        Request request = new Request.Builder()
                .url(config.getBaseUrl() + "/verify")
                .headers(buildStandardHeaders())
                .post(body)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                successCallback(null);
            }
        });
    }

    /**
     * Social login.
     *
     * @param providerId  the provider id
     * @param accessToken the access token
     * @param code        the code
     * @param callback    the callback
     */
    void socialLogin(@NonNull String providerId, String accessToken, String code, StormpathCallback<Void> callback) {
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "stormpath_social")
                .add("providerId", providerId)
                .add("accessToken", accessToken)
                .add("code", code)
                .build();

        Request request = new Request.Builder()
                .url(config.getBaseUrl() + Endpoints.OAUTH_TOKEN)
                .headers(buildStandardHeaders())
                .post(body)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                try {
                    // TODO: remove copy paste & separate out
                    SessionTokens sessionTokens = moshi.adapter(SessionTokens.class).fromJson(response.body().source());
                    if (StringUtils.isBlank(sessionTokens.getAccessToken())) {
                        failureCallback(new RuntimeException("access_token was not found in response. See debug logs for details."));
                        return;
                    }

                    if (StringUtils.isBlank(sessionTokens.getRefreshToken())) {
                        Stormpath.logger().e("There was no refresh_token in the login response!");
                    }

                    preferenceStore.setAccessToken(sessionTokens.getAccessToken());
                    preferenceStore.setRefreshToken(sessionTokens.getRefreshToken());
                    successCallback(null);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                if (e instanceof UnknownHostException || e instanceof SocketTimeoutException || e instanceof SocketException) {
                    failureCallback(new StormpathError(platform.networkErrorMessage(), e));
                } else {
                    failureCallback(e);
                }
            }
        });
    }

    /**
     * Social google code auth.
     *
     * @param authorizationCode the authorization code
     * @param application       the application
     * @param callback          the callback
     */
    void socialGoogleCodeAuth(String authorizationCode, SocialProviderConfiguration application, StormpathCallback<String> callback){

        Random state = new Random(10000000);

        String codeUrl = "https://www.googleapis.com/oauth2/v4/token";

        StringTokenizer multiTokenizer = new StringTokenizer(application.appId, ".");
        int tokens = multiTokenizer.countTokens();
        ArrayList<String> tokenArray = new ArrayList<String>();
        while(multiTokenizer.hasMoreTokens()){
            tokenArray.add(multiTokenizer.nextToken());
        }

        String clientId = "";
        for(int i = tokenArray.size()-1; i > -1; i--) {
            if(i != tokenArray.size()-1){
                clientId = clientId + "." + tokenArray.get(i);
            }
            else
            {
                clientId = clientId + tokenArray.get(i);
            }

        }

        //do network call, send broadcast since can't return result from threaded method
        RequestBody formBody = new FormBody.Builder()
                .add("client_id", clientId)
                .add("code", authorizationCode)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", application.urlScheme + ":/oauth2callback")
                .add("verifier", String.valueOf(Math.abs(state.nextInt())))
                .build();

        Request request = new Request.Builder()
                .url(codeUrl)
                .post(formBody)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<String>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<String> callback) {
                try {
                    successCallback(response.body().string());
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }

        });

    }

    private Headers buildStandardHeaders() {
        return buildStandardHeaders(null);
    }

    private Headers buildStandardHeaders(String accessToken) {
        Headers.Builder builder = new Headers.Builder();
        builder.add("Accept", "application/json");
        builder.add("X-Stormpath-Agent", "stormpath-sdk-android/" + config.VERSION + " Android/" + Build.VERSION.SDK_INT);


        if (StringUtils.isNotBlank(accessToken)) {
            builder.add("Authorization", "Bearer " + accessToken);
        }

        return builder.build();
    }

    private abstract class OkHttpCallback<T> implements Callback {

        private StormpathCallback<T> stormpathCallback;

        /**
         * Instantiates a new Ok http callback.
         *
         * @param stormpathCallback the stormpath callback
         */
        public OkHttpCallback(StormpathCallback<T> stormpathCallback) {
            this.stormpathCallback = stormpathCallback;
        }

        @Override
        public void onFailure(Call call, IOException e) {
            if (e instanceof UnknownHostException || e instanceof SocketTimeoutException || e instanceof SocketException) {
                failureCallback(new StormpathError(platform.networkErrorMessage(), e));
            } else {
                failureCallback(e);
            }
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            if (response.isSuccessful()) {
                onSuccess(response, stormpathCallback);
            } else {
                try {
                    StormpathError error = moshi.adapter(StormpathError.class).fromJson(response.body().source());
                    failureCallback(error);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        }

        /**
         * On success.
         *
         * @param response the response
         * @param callback the callback
         */
        protected abstract void onSuccess(Response response, StormpathCallback<T> callback);

        /**
         * Success callback.
         *
         * @param t the t
         */
        void successCallback(final T t) {
            callbackExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    stormpathCallback.onSuccess(t);
                }
            });
        }

        /**
         * Failure callback.
         *
         * @param t the t
         */
        void failureCallback(final Throwable t) {
            failureCallback(new StormpathError(platform.unknownErrorMessage(), t));
        }

        /**
         * Failure callback.
         *
         * @param error the error
         */
        void failureCallback(final StormpathError error) {
            callbackExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    stormpathCallback.onFailure(error);
                }
            });
        }
    }

    private static class UserProfileResponse implements Serializable {

        @Json(name = "account")
        private UserProfile userProfile;
    }

    private static class SocialLoginRequest implements Serializable {

        @Json(name = "providerData")
        private Map<String, String> providerData = new LinkedHashMap<>();

        /**
         * Instantiates a new Social login request.
         *
         * @param providerId  the provider id
         * @param accessToken the access token
         * @param code        the code
         */
        public SocialLoginRequest(String providerId, String accessToken, String code) {
            providerData.put("providerId", providerId);
            if (StringUtils.isNotBlank(accessToken)) {
                providerData.put("accessToken", accessToken);
            }
            if (StringUtils.isNotBlank(code)) {
                providerData.put("code", code);
            }
        }
    }
}
