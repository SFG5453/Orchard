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

#pragma once

#import <WebKit/WebKit.h>

#include "webkit_profile.h"

#include <QString>

// Objective-C++ only, compiled with ARC.

struct WebKitProfile::Native {
  WKWebsiteDataStore *store{nil};
  id<WKHTTPCookieStoreObserver> observer{nil};
};

namespace webkit_mac {

inline NSString *ns(const QString &text) {
  return text.toNSString();
}

// A string result as is, anything else as JSON, and an empty string for nothing.
inline QString jsResult(id value) {
  if (!value || value == [NSNull null])
    return {};
  if ([value isKindOfClass:[NSString class]])
    return QString::fromNSString(value);
  NSData *json = [NSJSONSerialization dataWithJSONObject:value options:NSJSONWritingFragmentsAllowed error:nil];
  return json ? QString::fromUtf8(static_cast<const char *>(json.bytes), int(json.length)) : QString();
}

} // namespace webkit_mac
