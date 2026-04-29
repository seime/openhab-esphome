#pragma once

#include <algorithm>

#include "esphome/components/media_player/media_player.h"
#include "esphome/core/component.h"

namespace esphome::fake_media_player_component {

class FakeMediaPlayer : public media_player::MediaPlayer, public Component {
 public:
  void setup() override {
    this->state = media_player::MEDIA_PLAYER_STATE_IDLE;
    this->volume = 0.25f;
    this->muted_ = false;
    this->publish_state();
  }

  media_player::MediaPlayerTraits get_traits() override {
    auto traits = media_player::MediaPlayerTraits();
    traits.set_supports_pause(true);
    traits.set_supports_turn_off_on(true);
    traits.add_feature_flags(media_player::MediaPlayerEntityFeature::REPEAT_SET |
                             media_player::MediaPlayerEntityFeature::CLEAR_PLAYLIST);

    auto &formats = traits.get_supported_formats();
    formats.push_back({
        .format = "mp3",
        .sample_rate = 44100,
        .num_channels = 1,
        .purpose = media_player::MediaPlayerFormatPurpose::PURPOSE_DEFAULT,
        .sample_bytes = 0,
    });

    return traits;
  }

  bool is_muted() const override { return this->muted_; }

 protected:
  void control(const media_player::MediaPlayerCall &call) override {
    if (call.get_volume().has_value()) {
      this->volume = std::clamp(*call.get_volume(), 0.0f, 1.0f);
    }

    if (call.get_command().has_value()) {
      switch (*call.get_command()) {
        case media_player::MEDIA_PLAYER_COMMAND_PLAY:
        case media_player::MEDIA_PLAYER_COMMAND_ENQUEUE:
          this->state = media_player::MEDIA_PLAYER_STATE_PLAYING;
          break;
        case media_player::MEDIA_PLAYER_COMMAND_PAUSE:
          this->state = media_player::MEDIA_PLAYER_STATE_PAUSED;
          break;
        case media_player::MEDIA_PLAYER_COMMAND_STOP:
        case media_player::MEDIA_PLAYER_COMMAND_CLEAR_PLAYLIST:
          this->state = media_player::MEDIA_PLAYER_STATE_IDLE;
          break;
        case media_player::MEDIA_PLAYER_COMMAND_MUTE:
          this->muted_ = true;
          break;
        case media_player::MEDIA_PLAYER_COMMAND_UNMUTE:
          this->muted_ = false;
          break;
        case media_player::MEDIA_PLAYER_COMMAND_TOGGLE:
          this->state = this->state == media_player::MEDIA_PLAYER_STATE_PLAYING
                            ? media_player::MEDIA_PLAYER_STATE_PAUSED
                            : media_player::MEDIA_PLAYER_STATE_PLAYING;
          break;
        case media_player::MEDIA_PLAYER_COMMAND_VOLUME_UP:
          this->volume = std::min(this->volume + 0.05f, 1.0f);
          break;
        case media_player::MEDIA_PLAYER_COMMAND_VOLUME_DOWN:
          this->volume = std::max(this->volume - 0.05f, 0.0f);
          break;
        case media_player::MEDIA_PLAYER_COMMAND_TURN_ON:
          this->state = media_player::MEDIA_PLAYER_STATE_ON;
          break;
        case media_player::MEDIA_PLAYER_COMMAND_TURN_OFF:
          this->state = media_player::MEDIA_PLAYER_STATE_OFF;
          break;
        case media_player::MEDIA_PLAYER_COMMAND_REPEAT_ONE:
        case media_player::MEDIA_PLAYER_COMMAND_REPEAT_OFF:
          break;
      }
    }

    if (call.get_media_url().has_value()) {
      this->state = call.get_announcement().value_or(false) ? media_player::MEDIA_PLAYER_STATE_ANNOUNCING
                                                             : media_player::MEDIA_PLAYER_STATE_PLAYING;
    }

    this->publish_state();
  }

  bool muted_{false};
};

}  // namespace esphome::fake_media_player_component
