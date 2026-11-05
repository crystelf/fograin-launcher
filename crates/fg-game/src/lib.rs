//! Game installation and launching (§7, §9).
//!
//! The crate is split into pure metadata code ([`version`], [`rules`]) and
//! effectful code ([`install`], [`launch`]). Pure code has no filesystem or
//! network access, which keeps version json merging, rules and argument
//! evaluation testable with the real Mojang fixtures.

pub mod install;
pub mod instance;
pub mod launch;
pub mod layout;
pub mod resolver;
pub mod rules;
pub mod version;

/// Splits a legacy `minecraftArguments` string.
///
/// Quotes group words; a backslash escapes the next character outside single
/// quotes. The format is shell-like enough that a plain `split_whitespace`
/// would corrupt custom version json files with quoted paths.
#[must_use]
pub fn split_command_line(input: &str) -> Vec<String> {
    let mut words = Vec::new();
    let mut word = String::new();
    let mut quote: Option<char> = None;
    let mut escaped = false;
    let mut started = false;

    for character in input.chars() {
        if escaped {
            word.push(character);
            escaped = false;
            started = true;
            continue;
        }
        if character == '\\' && quote != Some('\'') {
            escaped = true;
            started = true;
            continue;
        }
        if let Some(active) = quote {
            if character == active {
                quote = None;
            } else {
                word.push(character);
            }
            started = true;
            continue;
        }
        match character {
            '"' | '\'' => {
                quote = Some(character);
                started = true;
            }
            character if character.is_whitespace() => {
                if started {
                    words.push(std::mem::take(&mut word));
                    started = false;
                }
            }
            character => {
                word.push(character);
                started = true;
            }
        }
    }
    if started {
        words.push(word);
    }
    words
}

#[cfg(test)]
mod tests {
    use super::split_command_line;

    #[test]
    fn splits_plain_arguments() {
        assert_eq!(
            split_command_line("--username Steve --version 1.21.4"),
            ["--username", "Steve", "--version", "1.21.4"]
        );
    }

    #[test]
    fn keeps_quoted_paths_together() {
        assert_eq!(
            split_command_line(r#"--gameDir "/Users/me/My Games" --x"#),
            ["--gameDir", "/Users/me/My Games", "--x"]
        );
        assert_eq!(split_command_line("a 'b c' d"), ["a", "b c", "d"]);
    }

    #[test]
    fn handles_escapes_and_empty_input() {
        assert!(split_command_line("   ").is_empty());
        assert_eq!(split_command_line(r"a\ b c"), ["a b", "c"]);
    }
}
