/* Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#include "appearance/image_dimensions.h"

#include <QBuffer>
#include <QEventLoop>
#include <QImage>
#include <QQmlComponent>
#include <QQmlEngine>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QTextStream>
#include <QTimer>
#include <memory>

bool verifyImageDimensions(QTextStream &out) {
  QTemporaryDir directory;
  QImage image(720, 360, QImage::Format_RGB32);
  image.fill(Qt::red);
  ImageDimensions local;
  for (const auto *format : {"PNG", "JPG", "WEBP"}) {
    const QString path = directory.filePath(QString::fromLatin1(format));
    if (!image.save(path, format)) continue;
    local.setSource(QUrl::fromLocalFile(path));
    if (local.size() != image.size()) {
      out << "Image dimensions failed for " << format << '\n'; return false;
    }
  }
  local.setSource(QUrl::fromLocalFile(directory.filePath("missing")));
  if (local.size().isValid()) { out << "Missing image retained dimensions.\n"; return false; }

  QByteArray bytes;
  QBuffer encoded(&bytes);
  encoded.open(QIODevice::WriteOnly);
  if (!image.save(&encoded, "PNG") || bytes.size() < 100) return false;
  QTcpServer server;
  if (!server.listen(QHostAddress::LocalHost)) return false;
  int requests = 0;
  QObject::connect(&server, &QTcpServer::newConnection, &server, [&] {
    auto *socket = server.nextPendingConnection();
    QObject::connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
    QObject::connect(socket, &QTcpSocket::readyRead, socket, [&, socket] {
      QByteArray request = socket->property("request").toByteArray() + socket->readAll();
      socket->setProperty("request", request);
      if (!request.contains("\r\n\r\n") || socket->property("answered").toBool()) return;
      socket->setProperty("answered", true);
      ++requests;
      if (request.startsWith("GET /redirect ")) {
        socket->write("HTTP/1.1 302 Found\r\nLocation: /image\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        socket->disconnectFromHost();
        return;
      }
      socket->write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: "
                    + QByteArray::number(bytes.size()) + "\r\n\r\n");
      // Leave the body unfinished: dimensions must come from the header alone.
      if (!request.startsWith("GET /slow ")) socket->write(bytes.left(100));
    });
  });
  QQmlEngine engine;
  QQmlComponent component(&engine);
  component.setData("import Orchard\nImageDimensions {}", QUrl());
  std::unique_ptr<QObject> object(component.create());
  auto *remote = qobject_cast<ImageDimensions *>(object.get());
  if (!remote) { out << component.errorString(); return false; }
  auto wait = [](int ms) {
    QEventLoop loop;
    QTimer::singleShot(ms, &loop, &QEventLoop::quit);
    loop.exec();
  };
  const QString base = QString("http://127.0.0.1:%1/").arg(server.serverPort());
  remote->setSource(QUrl(base + "redirect"));
  for (int i = 0; i < 100 && !remote->size().isValid(); ++i) wait(10);
  if (remote->size() != image.size() || requests != 2) {
    out << "Remote image header/redirect failed: " << requests << " requests, "
        << remote->size().width() << " pixels.\n"; return false;
  }
  remote->setSource({});
  remote->setSource(QUrl(base + "redirect"));
  if (remote->size() != image.size() || requests != 2) {
    out << "Image dimensions cache was not reused.\n"; return false;
  }
  remote->setSource(QUrl(base + "slow"));
  wait(50);
  remote->setSource({});
  wait(50);
  if (remote->size().isValid()) { out << "Cancelled header request retained dimensions.\n"; return false; }
  out << "Image header checks passed: local formats, partial HTTP body, redirects, cache, cancellation.\n";
  return true;
}
