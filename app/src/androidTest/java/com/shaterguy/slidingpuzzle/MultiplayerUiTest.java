package com.shaterguy.slidingpuzzle;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MultiplayerUiTest {
 @Before public void reset(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();c.getSharedPreferences("puzzle",0).edit().clear().commit();}
 private MultiplayerSession engine(MultiplayerActivity activity) throws Exception {java.lang.reflect.Field retained=MultiplayerActivity.class.getDeclaredField("r");retained.setAccessible(true);Object r=retained.get(activity);java.lang.reflect.Field engine=r.getClass().getDeclaredField("engine");engine.setAccessible(true);return (MultiplayerSession)engine.get(r);}
 private void assertRecoverableErrorUi(String expectedState,String message,boolean permissionCallback) throws Exception {
  try(ActivityScenario<MultiplayerActivity> scenario=ActivityScenario.launch(MultiplayerActivity.class)){
   onView(withId(500)).perform(click());
   scenario.onActivity(activity->{try{MultiplayerSession session=engine(activity);if(permissionCallback){String permission=Build.VERSION.SDK_INT>=33?Manifest.permission.NEARBY_WIFI_DEVICES:Manifest.permission.ACCESS_FINE_LOCATION;activity.onRequestPermissionsResult(31,new String[]{permission},new int[]{PackageManager.PERMISSION_DENIED});}else session.externalError(expectedState,message);assertEquals(expectedState,session.state);assertEquals("",session.result);}catch(Exception e){throw new RuntimeException(e);}});
   onView(withText("멀티 연결 종료")).check(matches(isDisplayed()));onView(withText(message)).check(matches(isDisplayed()));onView(withId(610)).check(matches(isDisplayed()));onView(withId(611)).check(matches(isDisplayed()));onView(withText("이겼어요!")).check(doesNotExist());onView(withText("상대가 먼저 완성했어요")).check(doesNotExist());onView(withId(600)).check(doesNotExist());
  }
 }
 @Test public void multiplayerEntryIsSeparateFromSoloFlow(){
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   onView(withId(103)).check(matches(isDisplayed()));onView(withId(120)).perform(scrollTo(),click());onView(withText("멀티 대결")).check(matches(isDisplayed()));onView(withId(500)).perform(click());onView(withId(510)).check(matches(withText("가까운 기기와 직접 연결")));onView(withId(511)).check(matches(withText("같은 Wi‑Fi에서 연결")));
  }
 }
 @Test public void soloControlsStillExistBeforeMultiplayerEntry(){
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){onView(withId(101)).check(matches(isDisplayed()));onView(withId(102)).check(matches(isDisplayed()));onView(withId(103)).check(matches(isDisplayed()));onView(withId(104)).check(matches(isDisplayed()));onView(withId(120)).check(matches(isDisplayed()));}
 }
 @Test public void permissionUnsupportedAndDisconnectStatesRenderRecoverableNonResultUi() throws Exception {
  assertRecoverableErrorUi(MultiplayerSession.PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.",true);
  assertRecoverableErrorUi(MultiplayerSession.P2P_UNSUPPORTED,"이 기기에서는 직접 연결을 사용할 수 없습니다.",false);
  assertRecoverableErrorUi(MultiplayerSession.DISCONNECTED,"상대와의 연결이 끊겼습니다. 이번 대결은 승패 없이 종료되었습니다.",false);
 }
}
