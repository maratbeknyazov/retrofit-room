# Laboratory 10 Retrofit

Open this Gradle project in Android Studio with JDK 17 and SDK 35. Run the debug configuration. The list uses `per_page=5`; tap a login for its ID and avatar URL.

<details>
<summary>Demo video scenario (maximum 40 seconds)</summary>

[Watch the Android demo (under 40 seconds)](demo.mp4)

1. Check the dependencies in `app/build.gradle.kts` and the INTERNET permission in `AndroidManifest.xml`. The emulator recording begins with five logins. Tap one to show the ID and avatar URL.
2. Tap **Test 404** to show **Server error: 404**.
3. Turn off network, then tap **Reload**. Show **No network** and **Retry**. Tap Retry twice. On the third failed request total, Retry disappears and **Open Wi-Fi settings** appears.
4. Restore the connection and tap **Reload**. Show the five logins again. To demonstrate recovery through Retry as well, disconnect, tap Reload once, reconnect and tap Retry before exhausting the limit.
5. The end card records the BASIC request status and the temporary checks.

</details>

To reproduce the assignment's temporary variations:

- Change the Gson annotation to `@SerializedName("avatarUrll")`. Tap a login to show a null avatar value, then restore `avatar_url`. Unknown JSON fields such as `html_url` and `type` are skipped by Gson.
- To demonstrate a missing trailing slash, use `https://api.github.com/users` as the base URL. Retrofit rejects that URL with `IllegalArgumentException` because its path does not end in `/`. Restore `https://api.github.com/`. A bare hostname without a written slash is normalized to `/` by OkHttp and does not reproduce the error.
- Remove the INTERNET permission, reinstall and load the list. The API 36 emulator logged `java.lang.SecurityException: Permission denied (missing INTERNET permission?)`. Restore the permission before running the app normally.
- Find `Retrofit.Builder()` in Kotlin sources to see its one use in `ApiInstance`. In debug, filter Logcat for `OkHttp`. The release build sets the interceptor to NONE.

Observed on the emulator:

```text
--> GET https://api.github.com/users?per_page=5
<-- 200 https://api.github.com/users?per_page=5 (2738ms, unknown-length body)
<-- 404 https://api.github.com/users/no-such-login-xyz (350ms, unknown-length body)
Offline: java.net.UnknownHostException
Mistyped Gson key: avatarUrl: null
Missing slash: java.lang.IllegalArgumentException: baseUrl must end in /: https://api.github.com/users
```

```text
GitHubService is the interface. Retrofit reads @GET, substitutes a @Path into
the URL and appends @Query to the request. The base URL comes from ApiInstance.
The converter factory supplies a Gson converter for the declared response type.
OkHttp sends the HTTP request and receives the response. The interceptor logs
request and status lines in debug. For a successful response, the converter
reads the body and maps avatar_url to avatarUrl. Retrofit resumes the suspend
call with the result. An unsuccessful HTTP status becomes HttpException and
a network failure can throw IOException. safeApiCall maps them to ApiResult
so the ViewModel can publish a Success or Error state for Compose.
```
