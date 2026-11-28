package com.laotie.parse;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.druid.DbType;

/**
 * Splits a SQL script on top-level semicolons while respecting dialect-specific
 * quoting, comments, dollar quotes and escape rules.
 */
public final class ScriptSplitter {

    private ScriptSplitter() {
    }

    public static List<String> split(String sql, DbType dialect) {
        List<String> statements = new ArrayList<>();
        if (sql == null) {
            return statements;
        }
        boolean mysqlFamily = dialect == DbType.mysql || dialect == DbType.mariadb;
        boolean hiveFamily = dialect == DbType.hive || dialect == DbType.antspark;
        boolean hashComments = mysqlFamily;
        boolean postgres = dialect == DbType.postgresql;
        boolean nestedComments = postgres;
        boolean bracketQuotes = dialect == DbType.sqlserver;

        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escapeString = false;
        boolean rawString = false;
        String dollarTag = null;
        boolean lineComment = false;
        boolean blockComment = false;
        int blockDepth = 0;

        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            if (lineComment) {
                current.append(ch);
                if (ch == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                current.append(ch);
                if (nestedComments && ch == '/' && next == '*') {
                    current.append(next);
                    i++;
                    blockDepth++;
                } else if (ch == '*' && next == '/') {
                    current.append(next);
                    i++;
                    blockDepth--;
                    if (blockDepth <= 0) {
                        blockComment = false;
                    }
                }
                continue;
            }
            if (dollarTag != null) {
                if (sql.startsWith(dollarTag, i)) {
                    current.append(dollarTag);
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (quote != 0) {
                current.append(ch);
                if (escapeString && ch == '\\' && i + 1 < sql.length()) {
                    current.append(sql.charAt(++i));
                } else if (ch == quote) {
                    if (!rawString && next == quote) {
                        current.append(next);
                        i++;
                    } else {
                        quote = 0;
                        escapeString = false;
                        rawString = false;
                    }
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`'
                    || (bracketQuotes && ch == '[')) {
                quote = ch == '[' ? ']' : ch;
                rawString = dialect == DbType.antspark
                        && (ch == '\'' || ch == '"')
                        && isRawStringPrefix(sql, i);
                boolean backslashQuotes = mysqlFamily || hiveFamily;
                escapeString = !rawString && (backslashQuotes
                        ? (ch == '\'' || ch == '"')
                        : (ch == '\'' && isEscapeStringPrefix(sql, i)));
                current.append(ch);
            } else if (ch == '$' && postgres && isDollarQuoteStart(sql, i)) {
                String tag = dollarTagAt(sql, i);
                if (tag != null) {
                    dollarTag = tag;
                    current.append(tag);
                    i += tag.length() - 1;
                } else {
                    current.append(ch);
                }
            } else if (ch == '-' && next == '-'
                    && (!mysqlFamily || i + 2 >= sql.length()
                            || Character.isWhitespace(sql.charAt(i + 2)))) {
                lineComment = true;
                current.append(ch);
            } else if (hashComments && ch == '#') {
                lineComment = true;
                current.append(ch);
            } else if (ch == '/' && next == '*') {
                blockComment = true;
                blockDepth = 1;
                current.append(ch);
            } else if (ch == ';') {
                addStatement(statements, current, hashComments, nestedComments);
            } else {
                current.append(ch);
            }
        }
        addStatement(statements, current, hashComments, nestedComments);
        return statements;
    }

    public static boolean isIdentifierChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    private static boolean isDollarQuoteStart(String sql, int index) {
        return index == 0 || !isIdentifierChar(sql.charAt(index - 1));
    }

    private static boolean isRawStringPrefix(String sql, int quoteIndex) {
        if (quoteIndex == 0) {
            return false;
        }
        char prefix = sql.charAt(quoteIndex - 1);
        if (prefix != 'r' && prefix != 'R') {
            return false;
        }
        return quoteIndex - 1 == 0 || !isIdentifierChar(sql.charAt(quoteIndex - 2));
    }

    private static boolean isEscapeStringPrefix(String sql, int quoteIndex) {
        if (quoteIndex == 0) {
            return false;
        }
        char prefix = sql.charAt(quoteIndex - 1);
        if (prefix != 'E' && prefix != 'e') {
            return false;
        }
        return quoteIndex - 1 == 0 || !isIdentifierChar(sql.charAt(quoteIndex - 2));
    }

    private static String dollarTagAt(String sql, int start) {
        if (start + 1 < sql.length() && sql.charAt(start + 1) == '$') {
            return "$$";
        }
        int end = sql.indexOf('$', start + 1);
        if (end < 0) {
            return null;
        }
        String tag = sql.substring(start, end + 1);
        if (!tag.matches("\\$[A-Za-z_][A-Za-z0-9_]*\\$")) {
            return null;
        }
        return tag;
    }

    private static void addStatement(List<String> statements, StringBuilder current,
            boolean hashComments, boolean nestedComments) {
        String statement = current.toString().trim();
        current.setLength(0);
        if (!statement.isEmpty()
                && !isCommentOnly(statement, hashComments, nestedComments)) {
            statements.add(statement);
        }
    }

    private static boolean isCommentOnly(String statement, boolean hashComments,
            boolean nestedComments) {
        boolean lineComment = false;
        boolean blockComment = false;
        int blockDepth = 0;
        char quote = 0;
        StringBuilder visible = new StringBuilder();

        for (int i = 0; i < statement.length(); i++) {
            char ch = statement.charAt(i);
            char next = i + 1 < statement.length() ? statement.charAt(i + 1) : 0;
            if (lineComment) {
                if (ch == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (nestedComments && ch == '/' && next == '*') {
                    i++;
                    blockDepth++;
                } else if (ch == '*' && next == '/') {
                    i++;
                    blockDepth--;
                    if (blockDepth <= 0) {
                        blockComment = false;
                    }
                }
                continue;
            }
            if (quote != 0) {
                visible.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                visible.append(ch);
            } else if (ch == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (hashComments && ch == '#') {
                lineComment = true;
            } else if (ch == '/' && next == '*') {
                blockComment = true;
                blockDepth = 1;
                i++;
            } else if (!Character.isWhitespace(ch)) {
                visible.append(ch);
            }
        }
        return visible.length() == 0;
    }
}
