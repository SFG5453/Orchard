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

#include <iostream>
#include <string>

#include "planner/pair_scorer.h"

int main() {
  std::string line;
  while (std::getline(std::cin, line)) {
    try {
      const auto request = orchard::planner::Json::parse(line);
      if (request.contains("analyses")) {
        std::cout << orchard::planner::SortQueue(
            request["analyses"], request.value("initial", orchard::planner::Json(nullptr))) << '\n';
      } else {
        std::cout << orchard::planner::ScorePair(
            request.at("analysis"), request.at("nextAnalysis")) << '\n';
      }
    } catch (const std::exception& error) {
      std::cerr << error.what() << '\n';
      return 1;
    }
  }
}
