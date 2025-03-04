package org.thoughtcrime.securesms.components.settings.app.privacy

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.Navigation
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mobi.upod.timedurationpicker.TimeDurationPicker
import mobi.upod.timedurationpicker.TimeDurationPickerDialog
import org.signal.core.util.DimensionUnit
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.PassphraseChangeActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.ClickPreference
import org.thoughtcrime.securesms.components.settings.ClickPreferenceViewHolder
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsAdapter
import org.thoughtcrime.securesms.components.settings.DSLSettingsFragment
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.PreferenceModel
import org.thoughtcrime.securesms.components.settings.PreferenceViewHolder
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.crypto.MasterSecretUtil
import org.thoughtcrime.securesms.keyvalue.PhoneNumberPrivacyValues
import org.thoughtcrime.securesms.keyvalue.PhoneNumberPrivacyValues.PhoneNumberListingMode
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.stories.Stories
import org.thoughtcrime.securesms.stories.settings.custom.PrivateStorySettingsFragmentArgs
import org.thoughtcrime.securesms.stories.settings.story.PrivateStoryItem
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.ConversationUtil
import org.thoughtcrime.securesms.util.ExpirationUtil
import org.thoughtcrime.securesms.util.FeatureFlags
import org.thoughtcrime.securesms.util.ServiceUtil
import org.thoughtcrime.securesms.util.SpanUtil
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.adapter.mapping.LayoutFactory
import org.thoughtcrime.securesms.util.navigation.safeNavigate
import java.lang.Integer.max
import java.util.Locale
import java.util.concurrent.TimeUnit

private val TAG = Log.tag(PrivacySettingsFragment::class.java)

class PrivacySettingsFragment : DSLSettingsFragment(R.string.preferences__privacy) {

  private lateinit var viewModel: PrivacySettingsViewModel

  private val incognitoSummary: CharSequence by lazy {
    SpannableStringBuilder(getString(R.string.preferences__this_setting_is_not_a_guarantee))
      .append(" ")
      .append(
        SpanUtil.learnMore(requireContext(), ContextCompat.getColor(requireContext(), R.color.signal_text_primary)) {
          CommunicationActions.openBrowserLink(requireContext(), getString(R.string.preferences__incognito_keyboard_learn_more))
        }
      )
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshBlockedCount()
  }

  override fun bindAdapter(adapter: DSLSettingsAdapter) {
    adapter.registerFactory(ValueClickPreference::class.java, LayoutFactory(::ValueClickPreferenceViewHolder, R.layout.value_click_preference_item))
    PrivateStoryItem.register(adapter)

    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
    val repository = PrivacySettingsRepository()
    val factory = PrivacySettingsViewModel.Factory(sharedPreferences, repository)
    viewModel = ViewModelProvider(this, factory)[PrivacySettingsViewModel::class.java]

    viewModel.state.observe(viewLifecycleOwner) { state ->
      adapter.submitList(getConfiguration(state).toMappingModelList())
    }
  }

  private fun getConfiguration(state: PrivacySettingsState): DSLConfiguration {
    return configure {
      clickPref(
        title = DSLSettingsText.from(R.string.PrivacySettingsFragment__blocked),
        summary = DSLSettingsText.from(getString(R.string.PrivacySettingsFragment__d_contacts, state.blockedCount)),
        onClick = {
          Navigation.findNavController(requireView())
            .safeNavigate(R.id.action_privacySettingsFragment_to_blockedUsersActivity)
        }
      )

      dividerPref()

      if (FeatureFlags.phoneNumberPrivacy()) {
        sectionHeaderPref(R.string.preferences_app_protection__who_can)

        clickPref(
          title = DSLSettingsText.from(R.string.preferences_app_protection__see_my_phone_number),
          summary = DSLSettingsText.from(getWhoCanSeeMyPhoneNumberSummary(state.seeMyPhoneNumber)),
          onClick = {
            onSeeMyPhoneNumberClicked(state.seeMyPhoneNumber)
          }
        )

        clickPref(
          title = DSLSettingsText.from(R.string.preferences_app_protection__find_me_by_phone_number),
          summary = DSLSettingsText.from(getWhoCanFindMeByPhoneNumberSummary(state.findMeByPhoneNumber)),
          onClick = {
            onFindMyPhoneNumberClicked(state.findMeByPhoneNumber)
          }
        )

        dividerPref()
      }

      sectionHeaderPref(R.string.PrivacySettingsFragment__messaging)

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__read_receipts),
        summary = DSLSettingsText.from(R.string.preferences__if_read_receipts_are_disabled_you_wont_be_able_to_see_read_receipts),
        isChecked = state.readReceipts,
        onClick = {
          viewModel.setReadReceiptsEnabled(!state.readReceipts)
        }
      )

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__typing_indicators),
        summary = DSLSettingsText.from(R.string.preferences__if_typing_indicators_are_disabled_you_wont_be_able_to_see_typing_indicators),
        isChecked = state.typingIndicators,
        onClick = {
          viewModel.setTypingIndicatorsEnabled(!state.typingIndicators)
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.PrivacySettingsFragment__disappearing_messages)

      customPref(
        ValueClickPreference(
          value = DSLSettingsText.from(ExpirationUtil.getExpirationAbbreviatedDisplayValue(requireContext(), state.universalExpireTimer)),
          clickPreference = ClickPreference(
            title = DSLSettingsText.from(R.string.PrivacySettingsFragment__default_timer_for_new_changes),
            summary = DSLSettingsText.from(R.string.PrivacySettingsFragment__set_a_default_disappearing_message_timer_for_all_new_chats_started_by_you),
            onClick = {
              NavHostFragment.findNavController(this@PrivacySettingsFragment).safeNavigate(R.id.action_privacySettingsFragment_to_disappearingMessagesTimerSelectFragment)
            }
          )
        )
      )

      dividerPref()

      sectionHeaderPref(R.string.PrivacySettingsFragment__app_security)

      if (state.isObsoletePasswordEnabled) {
        switchPref(
          title = DSLSettingsText.from(R.string.preferences__enable_passphrase),
          summary = DSLSettingsText.from(R.string.preferences__lock_signal_and_message_notifications_with_a_passphrase),
          isChecked = true,
          onClick = {
            MaterialAlertDialogBuilder(requireContext()).apply {
              setTitle(R.string.ApplicationPreferencesActivity_disable_passphrase)
              setMessage(R.string.ApplicationPreferencesActivity_this_will_permanently_unlock_signal_and_message_notifications)
              setIcon(R.drawable.ic_warning)
              setPositiveButton(R.string.ApplicationPreferencesActivity_disable) { _, _ ->
                MasterSecretUtil.changeMasterSecretPassphrase(
                  activity,
                  KeyCachingService.getMasterSecret(context),
                  MasterSecretUtil.UNENCRYPTED_PASSPHRASE
                )
                TextSecurePreferences.setPasswordDisabled(activity, true)
                val intent = Intent(activity, KeyCachingService::class.java)
                intent.action = KeyCachingService.DISABLE_ACTION
                requireActivity().startService(intent)
                viewModel.refresh()
              }
              setNegativeButton(android.R.string.cancel, null)
              show()
            }
          }
        )

        clickPref(
          title = DSLSettingsText.from(R.string.preferences__change_passphrase),
          summary = DSLSettingsText.from(R.string.preferences__change_your_passphrase),
          onClick = {
            if (MasterSecretUtil.isPassphraseInitialized(activity)) {
              startActivity(Intent(activity, PassphraseChangeActivity::class.java))
            } else {
              Toast.makeText(
                activity,
                R.string.ApplicationPreferenceActivity_you_havent_set_a_passphrase_yet,
                Toast.LENGTH_LONG
              ).show()
            }
          }
        )

        switchPref(
          title = DSLSettingsText.from(R.string.preferences__inactivity_timeout_passphrase),
          summary = DSLSettingsText.from(R.string.preferences__auto_lock_signal_after_a_specified_time_interval_of_inactivity),
          isChecked = state.isObsoletePasswordTimeoutEnabled,
          onClick = {
            viewModel.setObsoletePasswordTimeoutEnabled(!state.isObsoletePasswordEnabled)
          }
        )

        clickPref(
          title = DSLSettingsText.from(R.string.preferences__inactivity_timeout_interval),
          onClick = {
            TimeDurationPickerDialog(
              context,
              { _: TimeDurationPicker?, duration: Long ->
                val timeoutMinutes = max(TimeUnit.MILLISECONDS.toMinutes(duration).toInt(), 1)
                viewModel.setObsoletePasswordTimeout(timeoutMinutes)
              },
              0, TimeDurationPicker.HH_MM
            ).show()
          }
        )
      } else {
        val isKeyguardSecure = ServiceUtil.getKeyguardManager(requireContext()).isKeyguardSecure

        switchPref(
          title = DSLSettingsText.from(R.string.preferences_app_protection__screen_lock),
          summary = DSLSettingsText.from(R.string.preferences_app_protection__lock_signal_access_with_android_screen_lock_or_fingerprint),
          isChecked = state.screenLock && isKeyguardSecure,
          isEnabled = isKeyguardSecure,
          onClick = {
            viewModel.setScreenLockEnabled(!state.screenLock)

            val intent = Intent(requireContext(), KeyCachingService::class.java)
            intent.action = KeyCachingService.LOCK_TOGGLED_EVENT
            requireContext().startService(intent)

            ConversationUtil.refreshRecipientShortcuts()
          }
        )

        clickPref(
          title = DSLSettingsText.from(R.string.preferences_app_protection__screen_lock_inactivity_timeout),
          summary = DSLSettingsText.from(getScreenLockInactivityTimeoutSummary(state.screenLockActivityTimeout)),
          isEnabled = isKeyguardSecure && state.screenLock,
          onClick = {
            TimeDurationPickerDialog(
              context,
              { _: TimeDurationPicker?, duration: Long ->
                val timeoutSeconds = TimeUnit.MILLISECONDS.toSeconds(duration)
                viewModel.setScreenLockTimeout(timeoutSeconds)
              },
              0, TimeDurationPicker.HH_MM
            ).show()
          }
        )
      }

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__screen_security),
        summary = DSLSettingsText.from(R.string.PrivacySettingsFragment__block_screenshots_in_the_recents_list_and_inside_the_app),
        isChecked = state.screenSecurity,
        onClick = {
          viewModel.setScreenSecurityEnabled(!state.screenSecurity)

          if (TextSecurePreferences.isScreenSecurityEnabled(requireContext())) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
          } else {
            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
          }
        }
      )

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__incognito_keyboard),
        summary = DSLSettingsText.from(R.string.preferences__request_keyboard_to_disable),
        isChecked = state.incognitoKeyboard,
        onClick = {
          viewModel.setIncognitoKeyboard(!state.incognitoKeyboard)
        }
      )

      textPref(
        summary = DSLSettingsText.from(incognitoSummary),
      )

      if (Stories.isFeatureAvailable()) {

        dividerPref()

        sectionHeaderPref(R.string.ConversationListTabs__stories)

        if (!SignalStore.storyValues().isFeatureDisabled) {
          customPref(
            PrivateStoryItem.RecipientModel(
              recipient = Recipient.self(),
              onClick = { findNavController().safeNavigate(R.id.action_privacySettings_to_myStorySettings) }
            )
          )

          space(DimensionUnit.DP.toPixels(24f).toInt())

          customPref(
            PrivateStoryItem.NewModel(
              onClick = {
                findNavController().safeNavigate(R.id.action_privacySettings_to_newPrivateStory)
              }
            )
          )

          state.privateStories.forEach {
            customPref(
              PrivateStoryItem.PartialModel(
                privateStoryItemData = it,
                onClick = { model ->
                  findNavController().safeNavigate(
                    R.id.action_privacySettings_to_privateStorySettings,
                    PrivateStorySettingsFragmentArgs.Builder(model.privateStoryItemData.id).build().toBundle()
                  )
                }
              )
            )
          }
        }

        switchPref(
          title = DSLSettingsText.from(R.string.PrivacySettingsFragment__share_and_view_stories),
          summary = DSLSettingsText.from(R.string.PrivacySettingsFragment__you_will_no_longer_be_able),
          isChecked = state.isStoriesEnabled,
          onClick = {
            viewModel.setStoriesEnabled(!state.isStoriesEnabled)
          }
        )
      }

      dividerPref()

      clickPref(
        title = DSLSettingsText.from(R.string.preferences__advanced),
        summary = DSLSettingsText.from(R.string.PrivacySettingsFragment__signal_message_and_calls),
        onClick = {
          Navigation.findNavController(requireView()).safeNavigate(R.id.action_privacySettingsFragment_to_advancedPrivacySettingsFragment)
        }
      )
    }
  }

  private fun getScreenLockInactivityTimeoutSummary(timeoutSeconds: Long): String {
    val hours = TimeUnit.SECONDS.toHours(timeoutSeconds)
    val minutes = TimeUnit.SECONDS.toMinutes(timeoutSeconds) - hours * 60

    return if (timeoutSeconds <= 0) {
      getString(R.string.AppProtectionPreferenceFragment_none)
    } else {
      String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
    }
  }

  @StringRes
  private fun getWhoCanSeeMyPhoneNumberSummary(phoneNumberSharingMode: PhoneNumberPrivacyValues.PhoneNumberSharingMode): Int {
    return when (phoneNumberSharingMode) {
      PhoneNumberPrivacyValues.PhoneNumberSharingMode.EVERYONE -> R.string.PhoneNumberPrivacy_everyone
      PhoneNumberPrivacyValues.PhoneNumberSharingMode.CONTACTS -> R.string.PhoneNumberPrivacy_my_contacts
      PhoneNumberPrivacyValues.PhoneNumberSharingMode.NOBODY -> R.string.PhoneNumberPrivacy_nobody
    }
  }

  @StringRes
  private fun getWhoCanFindMeByPhoneNumberSummary(phoneNumberListingMode: PhoneNumberListingMode): Int {
    return when (phoneNumberListingMode) {
      PhoneNumberListingMode.LISTED -> R.string.PhoneNumberPrivacy_everyone
      PhoneNumberListingMode.UNLISTED -> R.string.PhoneNumberPrivacy_nobody
    }
  }

  private fun onSeeMyPhoneNumberClicked(phoneNumberSharingMode: PhoneNumberPrivacyValues.PhoneNumberSharingMode) {
    val value = arrayOf(phoneNumberSharingMode)
    val items = items(requireContext())
    val modes: List<PhoneNumberPrivacyValues.PhoneNumberSharingMode> = ArrayList(items.keys)
    val modeStrings = items.values.toTypedArray()
    val selectedMode = modes.indexOf(value[0])

    MaterialAlertDialogBuilder(requireActivity()).apply {
      setTitle(R.string.preferences_app_protection__see_my_phone_number)
      setCancelable(true)
      setSingleChoiceItems(
        modeStrings,
        selectedMode
      ) { _: DialogInterface?, which: Int -> value[0] = modes[which] }
      setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
        val newSharingMode = value[0]
        Log.i(
          TAG,
          String.format(
            "PhoneNumberSharingMode changed to %s. Scheduling storage value sync",
            newSharingMode
          )
        )
        viewModel.setPhoneNumberSharingMode(value[0])
      }
      setNegativeButton(android.R.string.cancel, null)
      show()
    }
  }

  private fun items(context: Context): Map<PhoneNumberPrivacyValues.PhoneNumberSharingMode, CharSequence> {
    val map: MutableMap<PhoneNumberPrivacyValues.PhoneNumberSharingMode, CharSequence> = LinkedHashMap()
    map[PhoneNumberPrivacyValues.PhoneNumberSharingMode.EVERYONE] = titleAndDescription(
      context,
      context.getString(R.string.PhoneNumberPrivacy_everyone),
      context.getString(R.string.PhoneNumberPrivacy_everyone_see_description)
    )
    map[PhoneNumberPrivacyValues.PhoneNumberSharingMode.NOBODY] =
      context.getString(R.string.PhoneNumberPrivacy_nobody)
    return map
  }

  private fun titleAndDescription(
    context: Context,
    header: String,
    description: String
  ): CharSequence {
    return SpannableStringBuilder().apply {
      append("\n")
      append(header)
      append("\n")
      setSpan(
        TextAppearanceSpan(context, android.R.style.TextAppearance_Small),
        length,
        length,
        Spanned.SPAN_INCLUSIVE_INCLUSIVE
      )
      append(description)
      append("\n")
    }
  }

  fun onFindMyPhoneNumberClicked(phoneNumberListingMode: PhoneNumberListingMode) {
    val context = requireContext()
    val value = arrayOf(phoneNumberListingMode)
    MaterialAlertDialogBuilder(requireActivity()).apply {
      setTitle(R.string.preferences_app_protection__find_me_by_phone_number)
      setCancelable(true)
      setSingleChoiceItems(
        arrayOf(
          titleAndDescription(
            context,
            context.getString(R.string.PhoneNumberPrivacy_everyone),
            context.getString(R.string.PhoneNumberPrivacy_everyone_find_description)
          ),
          context.getString(R.string.PhoneNumberPrivacy_nobody)
        ),
        value[0].ordinal
      ) { _: DialogInterface?, which: Int -> value[0] = PhoneNumberListingMode.values()[which] }
      setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
        Log.i(
          TAG,
          String.format(
            "PhoneNumberListingMode changed to %s. Scheduling storage value sync",
            value[0]
          )
        )
        viewModel.setPhoneNumberListingMode(value[0])
      }
      setNegativeButton(android.R.string.cancel, null)
      show()
    }
  }

  private class ValueClickPreference(
    val value: DSLSettingsText,
    val clickPreference: ClickPreference
  ) : PreferenceModel<ValueClickPreference>(
    title = clickPreference.title,
    summary = clickPreference.summary,
    icon = clickPreference.icon,
    isEnabled = clickPreference.isEnabled
  ) {
    override fun areContentsTheSame(newItem: ValueClickPreference): Boolean {
      return super.areContentsTheSame(newItem) &&
        clickPreference == newItem.clickPreference &&
        value == newItem.value
    }
  }

  private class ValueClickPreferenceViewHolder(itemView: View) : PreferenceViewHolder<ValueClickPreference>(itemView) {
    private val clickPreferenceViewHolder = ClickPreferenceViewHolder(itemView)
    private val valueText: TextView = findViewById(R.id.value_client_preference_value)

    override fun bind(model: ValueClickPreference) {
      super.bind(model)
      clickPreferenceViewHolder.bind(model.clickPreference)
      valueText.text = model.value.resolve(context)
    }
  }
}
