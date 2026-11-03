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
#include "output_protection.h"

#include "content_decryption_module.h"

#ifdef ORCHARD_HDCP_SESSION
#include <windows.h>
#include <roapi.h>
#include <windows.media.protection.h>
#include <wrl/client.h>
#include <wrl/wrappers/corewrappers.h>

#include <chrono>
#include <thread>
#include <vector>

namespace {

using ABI::Windows::Foundation::IAsyncInfo;
using ABI::Windows::Media::Protection::HdcpProtection_On;
using ABI::Windows::Media::Protection::IHdcpSession;
using Microsoft::WRL::ComPtr;

constexpr auto kSettleTimeout = std::chrono::seconds(5);

// Lives for the process: dropping it lets Windows turn HDCP back off.
IHdcpSession *session() {
  static ComPtr<IHdcpSession> hdcp = [] {
    ComPtr<IHdcpSession> made;
    RoInitialize(RO_INIT_MULTITHREADED);
    ComPtr<IInspectable> instance;
    if (SUCCEEDED(RoActivateInstance(
            Microsoft::WRL::Wrappers::HStringReference(RuntimeClass_Windows_Media_Protection_HdcpSession).Get(),
            &instance)))
      instance.As(&made);
    return made;
  }();
  return hdcp.Get();
}

uint32_t linkType(DISPLAYCONFIG_VIDEO_OUTPUT_TECHNOLOGY technology) {
  switch (technology) {
  case DISPLAYCONFIG_OUTPUT_TECHNOLOGY_HD15:
    return cdm::kLinkTypeVGA;
  case DISPLAYCONFIG_OUTPUT_TECHNOLOGY_DVI:
    return cdm::kLinkTypeDVI;
  case DISPLAYCONFIG_OUTPUT_TECHNOLOGY_HDMI:
    return cdm::kLinkTypeHDMI;
  case DISPLAYCONFIG_OUTPUT_TECHNOLOGY_DISPLAYPORT_EXTERNAL:
    return cdm::kLinkTypeDisplayPort;
  case DISPLAYCONFIG_OUTPUT_TECHNOLOGY_INTERNAL:
  case DISPLAYCONFIG_OUTPUT_TECHNOLOGY_LVDS:
  case DISPLAYCONFIG_OUTPUT_TECHNOLOGY_DISPLAYPORT_EMBEDDED:
  case DISPLAYCONFIG_OUTPUT_TECHNOLOGY_UDI_EMBEDDED:
    return cdm::kLinkTypeInternal;
  case DISPLAYCONFIG_OUTPUT_TECHNOLOGY_MIRACAST:
    return cdm::kLinkTypeNetwork;
  default:
    return cdm::kLinkTypeUnknown;
  }
}

uint32_t activeLinks() {
  UINT32 pathCount = 0;
  UINT32 modeCount = 0;
  std::vector<DISPLAYCONFIG_PATH_INFO> paths;
  std::vector<DISPLAYCONFIG_MODE_INFO> modes;
  LONG status = ERROR_INSUFFICIENT_BUFFER;
  // The display set can change between sizing and reading.
  for (int attempt = 0; attempt < 3 && status == ERROR_INSUFFICIENT_BUFFER; ++attempt) {
    if (GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &pathCount, &modeCount) != ERROR_SUCCESS)
      return cdm::kLinkTypeUnknown;
    paths.resize(pathCount);
    modes.resize(modeCount);
    status = QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, &pathCount, paths.data(), &modeCount, modes.data(), nullptr);
  }
  if (status != ERROR_SUCCESS || !pathCount)
    return cdm::kLinkTypeUnknown;
  uint32_t links = 0;
  for (UINT32 i = 0; i < pathCount; ++i)
    links |= linkType(paths[i].targetInfo.outputTechnology);
  return links;
}

} // namespace

namespace output_protection {

void enable(uint32_t mask) {
  IHdcpSession *hdcp = session();
  if (!hdcp || !(mask & cdm::kProtectionHDCP))
    return;
  ComPtr<ABI::Windows::Foundation::__FIAsyncOperation_1_Windows__CMedia__CProtection__CHdcpSetProtectionResult_t>
      operation;
  ComPtr<IAsyncInfo> info;
  if (FAILED(hdcp->SetDesiredMinProtectionAsync(HdcpProtection_On, &operation)) || FAILED(operation.As(&info)))
    return;
  const auto deadline = std::chrono::steady_clock::now() + kSettleTimeout;
  ABI::Windows::Foundation::AsyncStatus status = ABI::Windows::Foundation::AsyncStatus::Started;
  while (SUCCEEDED(info->get_Status(&status)) && status == ABI::Windows::Foundation::AsyncStatus::Started &&
         std::chrono::steady_clock::now() < deadline)
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
}

void query(uint32_t &links, uint32_t &protection) {
  links = activeLinks();
  protection = 0;
  boolean on = false;
  if (IHdcpSession *hdcp = session(); hdcp && SUCCEEDED(hdcp->IsEffectiveProtectionAtLeast(HdcpProtection_On, &on)) && on)
    protection = cdm::kProtectionHDCP;
}

} // namespace output_protection

#else

namespace output_protection {

void enable(uint32_t) {}

void query(uint32_t &links, uint32_t &protection) {
  links = 0;
  protection = 0;
}

} // namespace output_protection

#endif
