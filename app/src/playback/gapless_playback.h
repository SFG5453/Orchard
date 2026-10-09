#pragma once

#include <QMediaPlayer>

// Buffering is a usable preload, not a reason to discard downloaded audio.
// Even a load still in progress is cheaper to finish than to restart.
inline bool canReuseGaplessMedia(QMediaPlayer::MediaStatus status,
                                QMediaPlayer::Error error) {
  if (error != QMediaPlayer::NoError)
    return false;
  switch (status) {
  case QMediaPlayer::LoadingMedia:
  case QMediaPlayer::LoadedMedia:
  case QMediaPlayer::StalledMedia:
  case QMediaPlayer::BufferingMedia:
  case QMediaPlayer::BufferedMedia:
    return true;
  default:
    return false;
  }
}
