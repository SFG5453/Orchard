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
#include "video_hole.h"

#include <QSGGeometryNode>
#include <QSGMaterial>
#include <QSGMaterialShader>

#include <cstring>

namespace {
class HoleShader final : public QSGMaterialShader {
public:
  HoleShader() {
    setShaderFileName(VertexStage, QStringLiteral(":/shaders/video_hole.vert.qsb"));
    setShaderFileName(FragmentStage, QStringLiteral(":/shaders/video_hole.frag.qsb"));
    setFlag(UpdatesGraphicsPipelineState);
  }

  bool updateUniformData(RenderState &state, QSGMaterial *, QSGMaterial *) override {
    if (!state.isMatrixDirty())
      return false;
    const QMatrix4x4 matrix = state.combinedMatrix();
    std::memcpy(state.uniformData()->data(), matrix.constData(), 64);
    return true;
  }

  bool updateGraphicsPipelineState(RenderState &, GraphicsPipelineState *pipeline, QSGMaterial *,
                                   QSGMaterial *) override {
    pipeline->blendEnable = true;
    pipeline->srcColor = pipeline->dstColor = GraphicsPipelineState::Zero;
    pipeline->srcAlpha = pipeline->dstAlpha = GraphicsPipelineState::Zero;
    return true;
  }
};

class HoleMaterial final : public QSGMaterial {
public:
  HoleMaterial() {
    // Blending keeps it in the back-to-front pass, after everything it has to erase.
    setFlag(Blending);
    setFlag(NoBatching);
  }
  QSGMaterialType *type() const override {
    static QSGMaterialType type;
    return &type;
  }
  QSGMaterialShader *createShader(QSGRendererInterface::RenderMode) const override { return new HoleShader; }
};
} // namespace

VideoHole::VideoHole(QQuickItem *parent) : QQuickItem(parent) { setFlag(ItemHasContents); }

QSGNode *VideoHole::updatePaintNode(QSGNode *old, UpdatePaintNodeData *) {
  auto *node = static_cast<QSGGeometryNode *>(old);
  if (!node) {
    node = new QSGGeometryNode;
    node->setGeometry(new QSGGeometry(QSGGeometry::defaultAttributes_Point2D(), 4));
    node->setMaterial(new HoleMaterial);
    node->setFlags(QSGNode::OwnsGeometry | QSGNode::OwnsMaterial);
  }
  QSGGeometry::updateRectGeometry(node->geometry(), boundingRect());
  node->markDirty(QSGNode::DirtyGeometry);
  return node;
}
