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

use std::collections::{HashMap, HashSet};

use oxc_ast::ast::*;
use oxc_ast_visit::{Visit, walk};
use oxc_semantic::Scoping;
use oxc_span::{GetSpan, Span};

use crate::builtins::builtin;
use crate::{Definition, member_name};

struct Dependencies<'s> {
    source: &'s str,
    scoping: &'s Scoping,
    root: Span,
    names: Vec<String>,
    seen: HashSet<String>,
}
impl Dependencies<'_> {
    fn add(&mut self, name: String) {
        if self.seen.insert(name.clone()) {
            self.names.push(name);
        }
    }
    fn external(&self, id: &IdentifierReference<'_>) -> bool {
        let Some(reference) = id.reference_id.get() else {
            return true;
        };
        let Some(symbol) = self.scoping.get_reference(reference).symbol_id() else {
            return true;
        };
        let span = self.scoping.symbol_span(symbol);
        !(span.start >= self.root.start && span.end <= self.root.end)
    }
    fn external_base(&self, expr: &Expression<'_>) -> bool {
        match expr.get_inner_expression() {
            Expression::Identifier(id) => self.external(id),
            Expression::StaticMemberExpression(m) => self.external_base(&m.object),
            Expression::ComputedMemberExpression(m) => self.external_base(&m.object),
            _ => false,
        }
    }
}
impl<'a> Visit<'a> for Dependencies<'_> {
    fn visit_identifier_reference(&mut self, id: &IdentifierReference<'a>) {
        if self.external(id) {
            self.add(id.name.to_string());
        }
    }
    fn visit_static_member_expression(&mut self, member: &StaticMemberExpression<'a>) {
        // Visit the base first so namespace objects precede their members.
        walk::walk_static_member_expression(self, member);
        if self.external_base(&member.object)
            && let Some(base) = member_name(&member.object, self.source)
        {
            self.add(format!("{base}.{}", member.property.name));
        }
    }
    fn visit_computed_member_expression(&mut self, member: &ComputedMemberExpression<'a>) {
        walk::walk_computed_member_expression(self, member);
        if self.external_base(&member.object)
            && let Some(base) = member_name(&member.object, self.source)
        {
            self.add(format!(
                "{base}[{}]",
                member.expression.span().source_text(self.source)
            ));
        }
    }
}

// Based on the strict JS extractor policy: constructing browser services or
// calling arbitrary player initialization functions is unnecessary for solving
// a signature. This is an extraction policy, not an execution sandbox.
fn safe(expr: &Expression<'_>) -> bool {
    match expr.get_inner_expression() {
        Expression::FunctionExpression(_)
        | Expression::ArrowFunctionExpression(_)
        | Expression::Identifier(_)
        | Expression::BooleanLiteral(_)
        | Expression::NullLiteral(_)
        | Expression::NumericLiteral(_)
        | Expression::StringLiteral(_)
        | Expression::RegExpLiteral(_) => true,
        Expression::ClassExpression(v) => {
            v.body.body.iter().all(|element| match element {
                ClassElement::MethodDefinition(m) => !m.computed,
                ClassElement::PropertyDefinition(p) => {
                    !p.computed && (!p.r#static || p.value.as_ref().is_none_or(safe))
                }
                _ => false,
            }) && v.heritage.as_ref().is_none_or(|e| {
                matches!(
                    e.expression,
                    Expression::Identifier(_) | Expression::StaticMemberExpression(_)
                )
            })
        }
        Expression::TemplateLiteral(v) => v.expressions.iter().all(safe),
        Expression::ArrayExpression(v) => v.elements.iter().all(|e| {
            matches!(e, ArrayExpressionElement::Elision(_)) || e.as_expression().is_some_and(safe)
        }),
        Expression::ObjectExpression(v) => v.properties.iter().all(|p| {
            let ObjectPropertyKind::ObjectProperty(p) = p else {
                return false;
            };
            !p.computed
                && p.kind == PropertyKind::Init
                && matches!(
                    p.value,
                    Expression::FunctionExpression(_)
                        | Expression::ArrowFunctionExpression(_)
                        | Expression::BooleanLiteral(_)
                        | Expression::NullLiteral(_)
                        | Expression::NumericLiteral(_)
                        | Expression::StringLiteral(_)
                        | Expression::RegExpLiteral(_)
                )
        }),
        Expression::UnaryExpression(v) => safe(&v.argument),
        Expression::BinaryExpression(v) => safe(&v.left) && safe(&v.right),
        Expression::LogicalExpression(v) => safe(&v.left) && safe(&v.right),
        Expression::StaticMemberExpression(v) => v.property.name == "prototype",
        Expression::CallExpression(v) => {
            let callee_safe = match &v.callee {
                Expression::Identifier(id) => builtin(id.name.as_str()),
                Expression::StaticMemberExpression(m) => {
                    safe(&m.object) && builtin(m.property.name.as_str())
                }
                _ => false,
            };
            callee_safe
                && v.arguments
                    .iter()
                    .all(|a| a.as_expression().is_some_and(safe))
        }
        Expression::NewExpression(v) => {
            matches!(&v.callee, Expression::Identifier(id) if builtin(id.name.as_str()))
                && v.arguments
                    .iter()
                    .all(|a| a.as_expression().is_some_and(safe))
        }
        _ => false,
    }
}

fn fallback(expr: &Expression<'_>) -> &'static str {
    match expr.get_inner_expression() {
        Expression::ObjectExpression(_)
        | Expression::NewExpression(_)
        | Expression::StaticMemberExpression(_)
        | Expression::ComputedMemberExpression(_)
        | Expression::LogicalExpression(_) => "{}",
        Expression::ArrayExpression(_) => "[]",
        _ => "undefined",
    }
}

pub(crate) struct Emitter<'a, 's> {
    pub(crate) source: &'s str,
    pub(crate) scoping: &'s Scoping,
    pub(crate) definitions: &'s [Definition<'a>],
    pub(crate) index: &'s HashMap<String, usize>,
    pub(crate) seen: HashSet<usize>,
    pub(crate) output: String,
}
impl Emitter<'_, '_> {
    pub(crate) fn emit(&mut self, id: usize, depth: usize) -> Result<(), String> {
        if !self.seen.insert(id) {
            return Ok(());
        }
        if depth > 512 {
            return Err("Player dependency graph is too deep".into());
        }
        let definition = &self.definitions[id];
        if let Some(init) = definition.init {
            let mut deps = Dependencies {
                source: self.source,
                scoping: self.scoping,
                root: init.span(),
                names: Vec::new(),
                seen: HashSet::new(),
            };
            deps.visit_expression(init);
            if !definition.variable
                && let Some((parent, _)) = definition.name.rsplit_once('.')
            {
                deps.add(
                    parent
                        .strip_suffix(".prototype")
                        .unwrap_or(parent)
                        .to_owned(),
                );
            }
            for name in deps.names {
                if builtin(&name) {
                    continue;
                }
                // Alias members bind their own prototype immediately before use.
                if definition
                    .alias
                    .as_ref()
                    .is_some_and(|(alias, _)| &name == alias)
                {
                    continue;
                }
                if let Some(&dependency) = self.index.get(&name) {
                    self.emit(dependency, depth + 1)?;
                }
            }
        }
        if let Some((alias, owner)) = &definition.alias {
            self.output
                .push_str(&format!("{alias} = {owner}.prototype;\n"));
        }
        let value = match definition.init {
            Some(init) if safe(init) => {
                if matches!(init, Expression::Identifier(id) if !self.index.contains_key(id.name.as_str()))
                {
                    "undefined"
                } else {
                    init.span().source_text(self.source)
                }
            }
            Some(init) => fallback(init),
            None => "undefined",
        };
        self.output.push_str(&format!(
            "{}{} = {};\n",
            if definition.variable { "var " } else { "" },
            definition.name,
            value
        ));
        for &member in &definition.members {
            self.emit(member, depth + 1)?;
        }
        Ok(())
    }
}
