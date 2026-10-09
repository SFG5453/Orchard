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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include <QDateTime>
#include <QHash>
#include <QImage>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QObject>
#include <QQuickImageProvider>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>

#include <functional>

class OrchardAccount;
class QNetworkReply;
class QQuickWindow;

// Bug reports through the Orchard account service. Each report is a GitHub
// issue; this mirrors its activity so the app can say when someone answers.
class SupportCenter final : public QObject
{
    Q_OBJECT

    Q_PROPERTY(bool githubLinked READ githubLinked NOTIFY githubChanged)
    Q_PROPERTY(QString githubLogin READ githubLogin NOTIFY githubChanged)
    Q_PROPERTY(QString githubAvatar READ githubAvatar NOTIFY githubChanged)
    Q_PROPERTY(bool githubLinking READ githubLinking NOTIFY githubChanged)
    Q_PROPERTY(bool loaded READ loaded NOTIFY reportsChanged)
    Q_PROPERTY(bool loading READ loading NOTIFY reportsChanged)
    Q_PROPERTY(QVariantList reports READ reports NOTIFY reportsChanged)
    Q_PROPERTY(int unread READ unread NOTIFY reportsChanged)
    Q_PROPERTY(QString repository READ repository NOTIFY reportsChanged)
    Q_PROPERTY(QVariantMap activeReport READ activeReport NOTIFY activeChanged)
    Q_PROPERTY(QVariantList activeEvents READ activeEvents NOTIFY activeChanged)
    Q_PROPERTY(bool activeLoading READ activeLoading NOTIFY activeChanged)
    Q_PROPERTY(bool submitting READ submitting NOTIFY submittingChanged)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY errorChanged)
    // The draft lives here so it survives the popup closing for a capture.
    Q_PROPERTY(QString draftKind MEMBER m_draftKind NOTIFY draftChanged)
    Q_PROPERTY(QString draftTitle MEMBER m_draftTitle NOTIFY draftChanged)
    Q_PROPERTY(QString draftBody MEMBER m_draftBody NOTIFY draftChanged)
    Q_PROPERTY(bool draftDiagnostics MEMBER m_draftDiagnostics NOTIFY draftChanged)
    Q_PROPERTY(QString screenshotUrl READ screenshotUrl NOTIFY screenshotChanged)
    Q_PROPERTY(QString screenshotInfo READ screenshotInfo NOTIFY screenshotChanged)
    Q_PROPERTY(QString snapshotUrl READ snapshotUrl NOTIFY screenshotChanged)
    Q_PROPERTY(QString diagnostics READ diagnostics NOTIFY diagnosticsChanged)

public:
    explicit SupportCenter(OrchardAccount *account, QObject *parent = nullptr);

    [[nodiscard]] bool githubLinked() const { return !m_githubLogin.isEmpty(); }
    [[nodiscard]] QString githubLogin() const { return m_githubLogin; }
    [[nodiscard]] QString githubAvatar() const { return m_githubAvatar; }
    [[nodiscard]] bool githubLinking() const { return m_githubLinking; }
    [[nodiscard]] bool loaded() const { return m_loaded; }
    [[nodiscard]] bool loading() const { return m_loading; }
    [[nodiscard]] QVariantList reports() const { return m_reports; }
    [[nodiscard]] int unread() const { return m_unread; }
    [[nodiscard]] QString repository() const { return m_repository; }
    [[nodiscard]] QVariantMap activeReport() const { return m_activeReport; }
    [[nodiscard]] QVariantList activeEvents() const { return m_activeEvents; }
    [[nodiscard]] bool activeLoading() const { return m_activeLoading; }
    [[nodiscard]] bool submitting() const { return m_submitting; }
    [[nodiscard]] QString errorMessage() const { return m_errorMessage; }
    [[nodiscard]] QString screenshotUrl() const;
    [[nodiscard]] QString screenshotInfo() const;
    [[nodiscard]] QString snapshotUrl() const;
    [[nodiscard]] QString diagnostics() const { return m_diagnostics; }
    [[nodiscard]] QImage screenshot() const { return m_screenshot; }
    [[nodiscard]] QImage snapshot() const { return m_snapshot; }

    Q_INVOKABLE void refresh();
    Q_INVOKABLE void openReport(const QString &id);
    Q_INVOKABLE void closeReport();
    Q_INVOKABLE void linkGithub();
    Q_INVOKABLE void cancelGithubLink();
    Q_INVOKABLE void unlinkGithub();
    Q_INVOKABLE void submit();
    Q_INVOKABLE void discardDraft();

    // Screens and diagnostics, in support_capture.cpp.
    Q_INVOKABLE void takeSnapshot(QQuickWindow *window, const QString &page);
    Q_INVOKABLE void captureWindow(QQuickWindow *window, const QString &page);
    Q_INVOKABLE void attachSnapshot();
    Q_INVOKABLE bool attachFile(const QUrl &url);
    Q_INVOKABLE void clearScreenshot();

    // Largest upload the service accepts, with a little room for the form.
    static constexpr qsizetype maxScreenshotBytes = 4800 * 1024;
    // PNG when it fits, then JPEG, then smaller JPEGs. Empty if nothing fits.
    static QByteArray encodeScreenshot(const QImage &image, QByteArray *mimeType);
    static QString summarizeUpdates(const QVariantList &previous, const QVariantList &current, bool firstLoad);

signals:
    void githubChanged();
    void reportsChanged();
    void activeChanged();
    void submittingChanged();
    void errorChanged();
    void draftChanged();
    void screenshotChanged();
    void diagnosticsChanged();
    // Short text for the app's notice pill.
    void notice(const QString &message);
    void submitted(const QString &url);

private:
    void setError(const QString &message);
    void setScreenshot(const QImage &image);
    void updateDiagnostics(QQuickWindow *window, const QString &page);
    void applyList(const QJsonObject &body);
    void clearAccountState();
    void markRead(const QString &id);
    void request(const QString &method, const QString &path, QByteArray body,
                 std::function<void(int, const QJsonObject &)> done);
    [[nodiscard]] QNetworkRequest authorized(const QString &path, const QString &token) const;

    OrchardAccount *m_account;
    QNetworkAccessManager m_network;
    QTimer m_poll;
    QTimer m_linkPoll;
    QDateTime m_lastRefresh;
    QDateTime m_linkDeadline;
    quint64 m_generation{0};

    QString m_githubLogin;
    QString m_githubAvatar;
    bool m_githubLinking{false};
    bool m_loaded{false};
    bool m_loading{false};
    QVariantList m_reports;
    int m_unread{0};
    QString m_repository;
    QVariantMap m_activeReport;
    QVariantList m_activeEvents;
    bool m_activeLoading{false};
    bool m_submitting{false};
    QString m_errorMessage;

    QString m_draftKind{QStringLiteral("bug")};
    QString m_draftTitle;
    QString m_draftBody;
    bool m_draftDiagnostics{true};
    QImage m_screenshot;
    QImage m_snapshot;
    // Bumped per image so QML's image cache never shows a stale one.
    int m_imageSerial{0};
    QString m_diagnostics;
};

// Serves image://support/screenshot/<n> and image://support/snapshot/<n> previews.
class SupportImageProvider final : public QQuickImageProvider
{
public:
    explicit SupportImageProvider(SupportCenter *support)
        : QQuickImageProvider(QQuickImageProvider::Image), m_support(support) {}

    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;
    // Scales down to fit; a zero or negative dimension leaves that side unbounded. Never upscales.
    static QImage fitWithin(const QImage &image, const QSize &requestedSize);

private:
    SupportCenter *m_support;
};
