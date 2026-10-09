/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */
#include "discord_sdk_presence.h"

#include <QDateTime>
#include <QRegularExpression>
#include <QString>
#include <cmath>

namespace {
QString field(const QJsonObject &p, const char *key)
{
    return p.value(QLatin1String(key)).toString();
}

QString clockText(double value)
{
    if (!std::isfinite(value) || value < 0) value = 0;
    const auto seconds = static_cast<quint64>(std::round(value));
    const auto minutes = (seconds / 60) % 60;
    if (seconds >= 3600)
        return QStringLiteral("%1:%2:%3").arg(seconds / 3600).arg(minutes, 2, 10, QLatin1Char('0'))
            .arg(seconds % 60, 2, 10, QLatin1Char('0'));
    return QStringLiteral("%1:%2").arg(seconds / 60).arg(seconds % 60, 2, 10, QLatin1Char('0'));
}

QString expand(const QString &value, const QJsonObject &p)
{
    static const QRegularExpression variable(QStringLiteral(R"(\{([^{}]*)\})"));
    QString output;
    qsizetype start = 0;
    auto matches = variable.globalMatch(value);
    while (matches.hasNext()) {
        const auto match = matches.next();
        output += value.mid(start, match.capturedStart() - start);
        const QString key = match.captured(1).trimmed();
        QString replacement;
        bool known = true;
        if (key == QLatin1String("title")) replacement = field(p, "song");
        else if (key == QLatin1String("artists")) replacement = field(p, "artist");
        else if (key == QLatin1String("position")) replacement = clockText(p.value(QLatin1String("currentTime")).toDouble());
        else if (key == QLatin1String("duration")) replacement = clockText(p.value(QLatin1String("duration")).toDouble());
        else if (key == QLatin1String("url")) replacement = field(p, "listenUrl");
        else if (QStringList{QStringLiteral("song"), QStringLiteral("artist"), QStringLiteral("album"),
                             QStringLiteral("platform"), QStringLiteral("app"), QStringLiteral("status")}.contains(key))
            replacement = p.value(key).toString();
        else known = false;
        output += known ? replacement : match.captured();
        start = match.capturedEnd();
    }
    output += value.mid(start);
    return output;
}

QString discordText(const QString &value)
{
    return value.simplified().left(128);
}

std::string utf8(const QString &value)
{
    return value.toUtf8().toStdString();
}

QString url(const QString &value)
{
    const QString clean = value.trimmed();
    return clean.startsWith(QLatin1String("https://")) || clean.startsWith(QLatin1String("http://"))
        ? clean : QString();
}

discordpp::ActivityTypes activityType(const QString &type)
{
    if (type == QLatin1String("playing")) return discordpp::ActivityTypes::Playing;
    if (type == QLatin1String("watching")) return discordpp::ActivityTypes::Watching;
    if (type == QLatin1String("competing")) return discordpp::ActivityTypes::Competing;
    return discordpp::ActivityTypes::Listening;
}

discordpp::StatusDisplayTypes statusDisplay(const QString &type)
{
    if (type == QLatin1String("state")) return discordpp::StatusDisplayTypes::State;
    if (type == QLatin1String("details")) return discordpp::StatusDisplayTypes::Details;
    return discordpp::StatusDisplayTypes::Name;
}
} // namespace

std::optional<discordpp::Activity> orchardDiscordActivity(const QJsonObject &p)
{
    if (field(p, "song").trimmed().isEmpty()) return std::nullopt;

    const bool playing = p.value(QLatin1String("isPlaying")).toBool();
    const QString activityTemplate = field(p, "activityText");
    const QString detailsTemplate = field(p, "detailsText");
    const QString chosenTemplate = activityTemplate.trimmed().isEmpty()
        ? (detailsTemplate.trimmed().isEmpty() ? QStringLiteral("{song}") : detailsTemplate)
        : activityTemplate;
    QString details = discordText(expand(chosenTemplate, p));
    if (!playing) details = discordText(details.isEmpty() ? QStringLiteral("Paused")
                                                          : QStringLiteral("Paused - ") + details);
    const QString configuredDetails = discordText(expand(detailsTemplate, p));
    const QString configuredState = discordText(expand(field(p, "stateText"), p));
    QString state = discordText(field(p, "mixText"));
    if (state.isEmpty()) state = discordText(field(p, "lyric"));
    if (state.isEmpty()) state = configuredState;
    if (state.isEmpty() && !activityTemplate.trimmed().isEmpty()) state = configuredDetails;
    if (state.isEmpty()) state = discordText(field(p, "artist"));

    discordpp::Activity activity;
    activity.SetType(activityType(field(p, "activityType")));
    activity.SetStatusDisplayType(statusDisplay(field(p, "statusDisplay")));
    // The SDK accepts this setter but Discord may replace it with the app's registered name.
    const QString name = discordText(expand(field(p, "platform"), p));
    if (!name.isEmpty()) activity.SetName(utf8(name));
    if (!details.isEmpty()) activity.SetDetails(utf8(details));
    if (!state.isEmpty()) activity.SetState(utf8(state));

    const double position = p.value(QLatin1String("currentTime")).toDouble();
    const double duration = p.value(QLatin1String("duration")).toDouble();
    if (playing && std::isfinite(position) && position >= 0) {
        const qint64 now = QDateTime::currentSecsSinceEpoch();
        discordpp::ActivityTimestamps timestamps;
        timestamps.SetStart(static_cast<quint64>(now - std::llround(position)) * 1000);
        if (std::isfinite(duration) && duration > position)
            timestamps.SetEnd(static_cast<quint64>(now + std::llround(duration - position)) * 1000);
        activity.SetTimestamps(timestamps);
    }

    const QString artwork = url(field(p, "artworkUrl"));
    const QString artistImage = url(field(p, "artistImageUrl"));
    const QString artworkText = discordText(expand(field(p, "artworkText"), p));
    if (!artwork.isEmpty() || !artistImage.isEmpty() || !artworkText.isEmpty()) {
        discordpp::ActivityAssets assets;
        if (!artwork.isEmpty()) assets.SetLargeImage(utf8(artwork));
        if (!artistImage.isEmpty()) assets.SetSmallImage(utf8(artistImage));
        if (!artworkText.isEmpty()) assets.SetLargeText(utf8(artworkText));
        const QString artistText = discordText(field(p, "artist"));
        assets.SetSmallText(utf8(artistImage.isEmpty() || artistText.isEmpty()
                                     ? (playing ? QStringLiteral("Playing") : QStringLiteral("Paused"))
                                     : artistText));
        activity.SetAssets(assets);
    }

    const QString listen = url(field(p, "listenUrl"));
    if (!listen.isEmpty()) {
        QString label = discordText(expand(field(p, "listenButtonText"), p));
        if (label.isEmpty()) label = discordText(expand(QStringLiteral("Listen on {platform}"), p));
        discordpp::ActivityButton button;
        button.SetLabel(utf8(label));
        button.SetUrl(utf8(listen));
        activity.AddButton(button);
    }
    if (p.value(QLatin1String("projectButton")).toBool()) {
        discordpp::ActivityButton button;
        button.SetLabel("View the Orchard Project");
        button.SetUrl("https://sfg545.dev/orchard");
        activity.AddButton(button);
    }
    return activity;
}
