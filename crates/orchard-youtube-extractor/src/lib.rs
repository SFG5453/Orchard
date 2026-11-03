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

//! Native AST parsing and dependency extraction for YouTube's signature player.
//!
//! Uses the WEB player matchers and an initializer policy based on the pinned
//! YouTube.js 18 extractor. Only selected source slices reach the JS runtime;
//! the downloaded player is never evaluated as a whole.

use std::collections::{HashMap, HashSet};
use std::time::Instant;

use oxc_allocator::Allocator;
use oxc_ast::ast::*;
use oxc_ast_visit::{Visit, walk};
use oxc_parser::Parser;
use oxc_semantic::SemanticBuilder;
use oxc_span::{GetSpan, SourceType};
use serde::Serialize;

const MAX_SOURCE_BYTES: usize = 16 * 1024 * 1024;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Extraction {
    pub output: String,
    pub exported: Vec<&'static str>,
    pub exported_raw_values: HashMap<&'static str, String>,
    pub timings: Timings,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Timings {
    pub parse_ms: f64,
    pub analyze_ms: f64,
    pub emit_ms: f64,
}

pub(crate) struct Definition<'a> {
    name: String,
    init: Option<&'a Expression<'a>>,
    variable: bool,
    // Prototype assignments are retained with their owner, even if a minified
    // alias is reused later for another constructor.
    members: Vec<usize>,
    alias: Option<(String, String)>,
}

fn binding_name<'a>(pattern: &'a BindingPattern<'a>) -> Option<&'a str> {
    match pattern {
        BindingPattern::BindingIdentifier(id) => Some(id.name.as_str()),
        _ => None,
    }
}

pub(crate) fn member_name(expr: &Expression<'_>, source: &str) -> Option<String> {
    match expr.get_inner_expression() {
        Expression::Identifier(id) => Some(id.name.to_string()),
        Expression::StaticMemberExpression(member) => Some(format!(
            "{}.{}",
            member_name(&member.object, source)?,
            member.property.name
        )),
        Expression::ComputedMemberExpression(member) => Some(format!(
            "{}[{}]",
            member_name(&member.object, source)?,
            member.expression.span().source_text(source)
        )),
        _ => None,
    }
}

fn target_name(target: &AssignmentTarget<'_>, source: &str) -> Option<String> {
    match target {
        AssignmentTarget::AssignmentTargetIdentifier(id) => Some(id.name.to_string()),
        AssignmentTarget::StaticMemberExpression(member) => Some(format!(
            "{}.{}",
            member_name(&member.object, source)?,
            member.property.name
        )),
        AssignmentTarget::ComputedMemberExpression(member) => Some(format!(
            "{}[{}]",
            member_name(&member.object, source)?,
            member.expression.span().source_text(source)
        )),
        _ => None,
    }
}

fn signature_function(expr: &Expression<'_>) -> bool {
    let Expression::FunctionExpression(function) = expr.get_inner_expression() else {
        return false;
    };
    let params = &function.params.items;
    if params.len() < 3
        || params[0].initializer.is_some()
        || params[1].initializer.is_none()
        || params[2].initializer.is_none()
    {
        return false;
    }
    let Some(url) = binding_name(&params[0].pattern) else {
        return false;
    };
    let Some(body) = &function.body else {
        return false;
    };
    let mut constructor = false;
    let mut alr = false;
    for statement in &body.statements {
        let Statement::ExpressionStatement(statement) = statement else {
            continue;
        };
        match statement.expression.get_inner_expression() {
            Expression::AssignmentExpression(assignment) if assignment.operator.is_assign() => {
                if let AssignmentTarget::AssignmentTargetIdentifier(id) = &assignment.left
                    && id.name == url
                    && let Expression::NewExpression(new) = &assignment.right
                {
                    constructor |= new.callee.is_member_expression();
                }
            }
            Expression::CallExpression(call)
                if call.callee.is_member_expression() && call.arguments.len() == 2 =>
            {
                alr |= matches!((&call.arguments[0], &call.arguments[1]),
                    (Argument::StringLiteral(a), Argument::StringLiteral(b)) if a.value == "alr" && b.value == "yes");
            }
            _ => {}
        }
    }
    constructor && alr
}

#[derive(Default)]
struct Timestamp(Option<u32>);
impl<'a> Visit<'a> for Timestamp {
    fn visit_object_property(&mut self, property: &ObjectProperty<'a>) {
        if self.0.is_none()
            && !property.computed
            && property.key.static_name().as_deref() == Some("signatureTimestamp")
            && let Expression::NumericLiteral(value) = &property.value
            && value.value > 0.0
            && value.value.fract() == 0.0
            && value.value <= u32::MAX as f64
        {
            self.0 = Some(value.value as u32);
        }
        if self.0.is_none() {
            walk::walk_object_property(self, property);
        }
    }
}

pub fn extract(source: &str) -> Result<Extraction, String> {
    if source.len() > MAX_SOURCE_BYTES {
        return Err("Player script exceeds the 16 MiB limit".into());
    }
    let start = Instant::now();
    // The AST needs about 16x the source. One chunk above glibc's 32 MiB mmap
    // cap is unmapped on drop instead of staying in the heap.
    let allocator = Allocator::with_capacity(source.len() * 17);
    let parsed = Parser::new(&allocator, source, SourceType::cjs()).parse();
    if parsed.fatal_error || !parsed.diagnostics.is_empty() {
        return Err("Could not parse YouTube player JavaScript".into());
    }
    let parse_ms = start.elapsed().as_secs_f64() * 1000.0;
    let analyze_start = Instant::now();
    let semantics = SemanticBuilder::new()
        .with_build_nodes(false)
        .build(&parsed.program);
    if !semantics.diagnostics.is_empty() {
        return Err("Could not resolve YouTube player bindings".into());
    }
    let function = parsed
        .program
        .body
        .iter()
        .find_map(|statement| {
            let Statement::ExpressionStatement(s) = statement else {
                return None;
            };
            let Expression::CallExpression(call) = s.expression.get_inner_expression() else {
                return None;
            };
            let Expression::FunctionExpression(function) = call.callee.get_inner_expression()
            else {
                return None;
            };
            Some(function)
        })
        .ok_or("Could not find the YouTube player wrapper")?;
    let parameter = function
        .params
        .items
        .first()
        .and_then(|p| binding_name(&p.pattern))
        .ok_or("Could not find the YouTube player namespace")?;
    let body = function
        .body
        .as_ref()
        .ok_or("YouTube player wrapper has no body")?;
    let mut definitions: Vec<Definition<'_>> = Vec::new();
    let mut index = HashMap::new();
    let mut alias: Option<(String, String, usize)> = None;
    for statement in &body.statements {
        match statement {
            Statement::VariableDeclaration(vars) => {
                alias = None;
                for decl in &vars.declarations {
                    let Some(name) = binding_name(&decl.id) else {
                        continue;
                    };
                    index.insert(name.to_owned(), definitions.len());
                    definitions.push(Definition {
                        name: name.into(),
                        init: decl.init.as_ref(),
                        variable: true,
                        members: Vec::new(),
                        alias: None,
                    });
                }
            }
            Statement::ExpressionStatement(s) => {
                let Expression::AssignmentExpression(a) = s.expression.get_inner_expression()
                else {
                    alias = None;
                    continue;
                };
                if !a.operator.is_assign() {
                    alias = None;
                    continue;
                }
                let Some(name) = target_name(&a.left, source) else {
                    alias = None;
                    continue;
                };
                if member_name(&a.right, source).as_deref() == Some(&name) {
                    continue;
                }
                if let Expression::StaticMemberExpression(m) = &a.right
                    && m.property.name == "prototype"
                    && let Some(owner) = member_name(&m.object, source)
                    && let Some(&owner_id) = index.get(&owner)
                {
                    alias = Some((name.clone(), owner, owner_id));
                }
                let binding = alias
                    .as_ref()
                    .filter(|(key, _, _)| {
                        name.strip_prefix(key)
                            .is_some_and(|rest| rest.starts_with(['.', '[']))
                    })
                    .cloned();
                if let Some((key, owner, owner_id)) = binding {
                    let id = definitions.len();
                    definitions.push(Definition {
                        name,
                        init: Some(&a.right),
                        variable: false,
                        members: Vec::new(),
                        alias: Some((key, owner)),
                    });
                    definitions[owner_id].members.push(id);
                    continue;
                }
                if alias.as_ref().is_some_and(|(key, _, _)| key != &name) {
                    alias = None;
                }
                if let AssignmentTarget::AssignmentTargetIdentifier(_) = &a.left {
                    if let Some(&id) = index.get(&name) {
                        definitions[id].init = Some(&a.right);
                    }
                } else if !index.contains_key(&name) {
                    let id = definitions.len();
                    // Direct prototype assignments need the same retention as aliases.
                    if let Some((owner, member)) = name.split_once(".prototype")
                        && member.starts_with(['.', '['])
                        && let Some(&owner_id) = index.get(owner)
                    {
                        definitions[owner_id].members.push(id);
                    }
                    index.insert(name.clone(), id);
                    definitions.push(Definition {
                        name,
                        init: Some(&a.right),
                        variable: false,
                        members: Vec::new(),
                        alias: None,
                    });
                }
            }
            _ => {
                alias = None;
            }
        }
    }
    let target = definitions
        .iter()
        .position(|d| d.variable && d.init.is_some_and(signature_function))
        .ok_or("Could not find the YouTube signature function")?;
    let mut timestamp = Timestamp::default();
    for definition in &definitions {
        if definition.variable
            && let Some(Expression::FunctionExpression(f)) = definition.init
            && let Some(body) = &f.body
        {
            timestamp.visit_function_body(body);
        }
        if timestamp.0.is_some() {
            break;
        }
    }
    let timestamp = timestamp
        .0
        .ok_or("Could not find the YouTube signature timestamp")?;
    let analyze_ms = analyze_start.elapsed().as_secs_f64() * 1000.0;
    let emit_start = Instant::now();
    let mut emitter = Emitter {
        source,
        scoping: semantics.semantic.scoping(),
        definitions: &definitions,
        index: &index,
        seen: HashSet::new(),
        output: String::new(),
    };
    emitter.emit(target, 0)?;
    let output = format!(
        "const exportedVars = (function({parameter}) {{\nconst window = Object.create(null);\nconst document = {{}};\nconst self = window;\n{}\nreturn {{ nsigFunction: {} }};\n}})({{}});\n",
        emitter.output, definitions[target].name
    );
    Ok(Extraction {
        output,
        exported: vec!["nsigFunction"],
        exported_raw_values: HashMap::from([("signatureTimestampVar", timestamp.to_string())]),
        timings: Timings {
            parse_ms,
            analyze_ms,
            emit_ms: emit_start.elapsed().as_secs_f64() * 1000.0,
        },
    })
}

mod builtins;
mod emit;
/// C ABI shared by the desktop and Android provider hosts.
pub mod ffi;
use emit::Emitter;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_invalid_or_unrecognized_players() {
        for source in ["", "(function(", "(function(g) { var x = 1; })(this);"] {
            assert!(extract(source).is_err());
        }
        assert!(extract(&" ".repeat(MAX_SOURCE_BYTES + 1)).is_err());
    }

    #[test]
    fn does_not_match_markers_in_strings_or_comments() {
        assert!(
            extract(
                r#"(function(g) {
            // signatureTimestamp: 20702
            var x = function() { return "url=new g.URL(url);url.set('alr','yes')"; };
        })(this);"#
            )
            .is_err()
        );
    }
}
