package com.shaterguy.slidingpuzzle;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class MultiplayerUiTest {
 @Before public void reset(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();c.getSharedPreferences("puzzle",0).edit().clear().commit();}
 @Test public void multiplayerEntryIsSeparateFromSoloFlow(){
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   onView(withId(103)).check(matches(isDisplayed()));onView(withId(120)).perform(scrollTo(),click());onView(withText("멀티 대결")).check(matches(isDisplayed()));onView(withId(500)).perform(click());onView(withId(510)).check(matches(withText("가까운 기기와 직접 연결")));onView(withId(511)).check(matches(withText("같은 Wi‑Fi에서 연결")));
  }
 }
 @Test public void soloControlsStillExistBeforeMultiplayerEntry(){
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){onView(withId(101)).check(matches(isDisplayed()));onView(withId(102)).check(matches(isDisplayed()));onView(withId(103)).check(matches(isDisplayed()));onView(withId(104)).check(matches(isDisplayed()));onView(withId(120)).check(matches(isDisplayed()));}
 }
}
