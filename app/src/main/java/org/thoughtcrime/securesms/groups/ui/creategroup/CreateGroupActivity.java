package org.thoughtcrime.securesms.groups.ui.creategroup;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.ContactSelectionActivity;
import org.thoughtcrime.securesms.ContactSelectionListFragment;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.contacts.ContactsCursorLoader;
import org.thoughtcrime.securesms.contacts.sync.ContactDiscovery;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.groups.ui.creategroup.details.AddGroupDetailsActivity;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.Stopwatch;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CreateGroupActivity extends ContactSelectionActivity {

  private static final String TAG = Log.tag(CreateGroupActivity.class);

  private static final short REQUEST_CODE_ADD_DETAILS = 17275;

  private ExtendedFloatingActionButton next;
  private ValueAnimator                padStart;
  private ValueAnimator                padEnd;

  public static Intent newIntent(@NonNull Context context) {
    Intent intent = new Intent(context, CreateGroupActivity.class);

    intent.putExtra(ContactSelectionListFragment.REFRESHABLE, false);
    intent.putExtra(ContactSelectionActivity.EXTRA_LAYOUT_RES_ID, R.layout.create_group_activity);

    int displayMode = Util.isDefaultSmsProvider(context) ? ContactsCursorLoader.DisplayMode.FLAG_SMS | ContactsCursorLoader.DisplayMode.FLAG_PUSH
                                                         : ContactsCursorLoader.DisplayMode.FLAG_PUSH;

    intent.putExtra(ContactSelectionListFragment.DISPLAY_MODE, displayMode);
    intent.putExtra(ContactSelectionListFragment.SELECTION_LIMITS, FeatureFlags.groupLimits().excludingSelf());

    return intent;
  }

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    super.onCreate(bundle, ready);
    assert getSupportActionBar() != null;
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    next = findViewById(R.id.next);
    extendSkip();

    next.setOnClickListener(v -> handleNextPressed());
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    if (requestCode == REQUEST_CODE_ADD_DETAILS && resultCode == RESULT_OK) {
      finish();
    } else {
      super.onActivityResult(requestCode, resultCode, data);
    }
  }

  @Override
  public void onBeforeContactSelected(@NonNull Optional<RecipientId> recipientId, String number, @NonNull Consumer<Boolean> callback) {
    if (contactsFragment.hasQueryFilter()) {
      getContactFilterView().clear();
    }

    shrinkSkip();

    callback.accept(true);
  }

  @Override
  public void onContactDeselected(@NonNull Optional<RecipientId> recipientId, String number) {
    if (contactsFragment.hasQueryFilter()) {
      getContactFilterView().clear();
    }

    if (contactsFragment.getSelectedContactsCount() == 0) {
      extendSkip();
    }
  }

  @Override
  public void onSelectionChanged() {
    int selectedContactsCount = contactsFragment.getTotalMemberCount();
    if (selectedContactsCount == 0) {
      getToolbar().setTitle(getString(R.string.CreateGroupActivity__select_members));
    } else {
      getToolbar().setTitle(getResources().getQuantityString(R.plurals.CreateGroupActivity__d_members, selectedContactsCount, selectedContactsCount));
    }
  }

  private void extendSkip() {
    next.setIconGravity(MaterialButton.ICON_GRAVITY_END);
    next.extend();
    animatePadding(24, 18);
  }

  private void shrinkSkip() {
    next.setIconGravity(MaterialButton.ICON_GRAVITY_START);
    next.shrink();
    animatePadding(16, 16);
  }

  private void animatePadding(int startDp, int endDp) {
    if (padStart != null) padStart.cancel();

    padStart = ValueAnimator.ofInt(next.getPaddingStart(), ViewUtil.dpToPx(startDp)).setDuration(200);
    padStart.addUpdateListener(animation -> {
      ViewUtil.setPaddingStart(next, (Integer) animation.getAnimatedValue());
    });
    padStart.start();

    if (padEnd != null) padEnd.cancel();

    padEnd = ValueAnimator.ofInt(next.getPaddingEnd(), ViewUtil.dpToPx(endDp)).setDuration(200);
    padEnd.addUpdateListener(animation -> {
      ViewUtil.setPaddingEnd(next, (Integer) animation.getAnimatedValue());
    });
    padEnd.start();
  }

  private void handleNextPressed() {
    Stopwatch                              stopwatch         = new Stopwatch("Recipient Refresh");
    SimpleProgressDialog.DismissibleDialog dismissibleDialog = SimpleProgressDialog.showDelayed(this);

    SimpleTask.run(getLifecycle(), () -> {
      List<RecipientId> ids = contactsFragment.getSelectedContacts()
                                              .stream()
                                              .map(selectedContact -> selectedContact.getOrCreateRecipientId(this))
                                              .collect(Collectors.toList());

      List<Recipient> resolved = Recipient.resolvedList(ids);

      stopwatch.split("resolve");

      Set<Recipient> registeredChecks = resolved.stream()
                                                .filter(r -> r.getRegistered() == RecipientDatabase.RegisteredState.UNKNOWN)
                                                .collect(Collectors.toSet());

      Log.i(TAG, "Need to do " + registeredChecks.size() + " registration checks.");

      for (Recipient recipient : registeredChecks) {
        try {
          ContactDiscovery.refresh(this, recipient, false);
        } catch (IOException e) {
          Log.w(TAG, "Failed to refresh registered status for " + recipient.getId(), e);
        }
      }

      stopwatch.split("registered");

      return ids;
    }, recipientIds -> {
      dismissibleDialog.dismiss();
      stopwatch.stop(TAG);
      startActivityForResult(AddGroupDetailsActivity.newIntent(this, recipientIds), REQUEST_CODE_ADD_DETAILS);
    });
  }
}
