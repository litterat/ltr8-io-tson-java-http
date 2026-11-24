#!/usr/bin/env bash
# Reports dangling cross-references in the documentation, as file:line: what is wrong.
#
#   scripts/check-doc-references.sh              checks the whole working tree
#   scripts/check-doc-references.sh --files F... restricts checks 1-5 to the named files (check 6 still runs)
#
# Checks: (1) every design/, proposal/ and experiments/ path names a file that exists; (2) a section citation, a note
# path then a comma and a quoted title, names a heading or a bold lead-in of that note (a prefix is enough); (3) a
# backticked test or probe class, with or without a method, names a Java file that holds it (the sibling tson-java
# checkout counts); (4) an UPSTREAM.md entry citation by title names a heading of UPSTREAM.md; (5) no citation by
# register number; (6) no design note over 20 KB. Skips build/, .gradle/, .git/, .claude/skills/, .claude/agents/ and
# scratchpad/. Prints nothing and exits 0 when clean, exits 1 otherwise.
set -euo pipefail
cd "$(dirname "$0")/.."
exec perl -CSD -x "$0" "$@"
#!perl
use strict; use warnings; use File::Find;

my @only; my $restrict = 0;
if (@ARGV && $ARGV[0] eq '--files') { shift @ARGV; $restrict = 1; @only = map { s{^\./}{}r } @ARGV; }

my (@files, %java);
find({ no_chdir => 1, wanted => sub {
    my $p = $File::Find::name; $p =~ s{^\./}{};
    if (-d $p) {
      $File::Find::prune = 1 if $p =~ m{(^|/)(build|\.gradle|\.git|scratchpad)$}
        || $p eq '.claude/skills' || $p eq '.claude/agents';
      return;
    }
    return unless $p =~ /\.(md|java|tn|kts|sh)$/;
    push @files, $p;
    push @{ $java{$1} }, $p if $p =~ m{([^/]+)\.java$};
  } }, '.');
@files = sort @files;
my %exists = map { $_ => 1 } @files;

sub slurp { my $f = shift; open(my $h, '<:encoding(UTF-8)', $f) or return ''; local $/; my $t = <$h>; close $h; $t }
sub norm {
  my $s = shift; $s =~ s/`//g; $s =~ s/\{\@(?:code|literal) ([^}]*)\}/$1/g; $s =~ s/\s+/ /g; $s =~ s/^ | $//g; $s
}

my @out;
sub report { push @out, "$_[0]:$_[1]: $_[2]" }

# Titles per design note: headings and bold lead-ins.
my %titles;
for my $f (grep { m{^design/[^/]+\.md$} } @files) {
  my @t; my $fence = 0; my @l = split /\n/, slurp($f);
  for my $i (0 .. $#l) {
    if ($l[$i] =~ /^\s*```/) { $fence = !$fence; next }
    next if $fence;
    if ($l[$i] =~ /^#+\s+(.*?)\s*$/) { push @t, norm($1); next }
    if ($l[$i] =~ /^\s*(?:>\s*)?(?:(?:[-*]|\d+\.)\s+)?(\*\*.*)$/) {
      my $s = $1; $s .= ' ' . ($l[$i + 1] // '') if $s !~ /^\*\*.+?\*\*/;
      push @t, norm($1) if $s =~ /^\*\*(.+?)\*\*/;
    }
  }
  $titles{$f} = \@t;
}
sub prefix_of { my ($want, $have) = @_; for (@$have) { return 1 if index($_, $want) == 0 } 0 }

# UPSTREAM.md headings.
my @up;
for (split /\n/, slurp('UPSTREAM.md')) { push @up, norm($1) if /^## \d+\.\s+(.*)$/ || /^### To file:\s+(.*)$/ }

# A class cited as upstream's lives in the sibling checkout, when one is present.
sub sibling {
  my $c = shift;
  -d '../ltr8-io-tson-java' && `find ../ltr8-io-tson-java -name '$c.java' -not -path '*/build/*' 2>/dev/null` ne '';
}
my %src;
sub javacontains {
  my ($cls, $m) = @_;
  for my $f (@{ $java{$cls} // [] }) {
    my $t = ($src{$f} //= slurp($f));
    return 1 if $t =~ /\bvoid\s+\Q$m\E\s*\(/;
  }
  0;
}

my @targets = $restrict ? grep { $exists{$_} } @only : @files;
for my $f (@targets) {
  my $text = slurp($f); my @lines = split /\n/, $text, -1;
  my $isdoc = $f =~ /\.(md|java)$/;

  # Checks 1, 5 and 3, per line.
  for my $i (0 .. $#lines) {
    my $l = $lines[$i]; my $n = $i + 1;
    while ($l =~ m{(?<![\w/.-])design/([\w-]+)\.md}g) {
      report($f, $n, "no such note: design/$1.md") unless $exists{"design/$1.md"};
    }
    while ($l =~ m{(?<![\w/.-])proposal/([\w-]+)\.md}g) {
      report($f, $n, "no such proposal: proposal/$1.md") unless $exists{"proposal/$1.md"};
    }
    while ($l =~ m{(?<![\w/.-])experiments/([\w-]+(?:/[\w-]+)*)/README\.md}g) {
      report($f, $n, "no such file: experiments/$1/README.md") unless $exists{"experiments/$1/README.md"};
    }
    next unless $isdoc;
    report($f, $n, "citation by register number: $&") while $l =~ /(?:UPSTREAM|SPEC-FEEDBACK)\.md[`}]*\s*#\d+/g;
    next unless $f =~ /\.md$/;
    while ($l =~ /`([A-Z]\w*(?:Test|Probe))(?:\.([a-z]\w*)(?:\(\))?)?`/g) {
      my ($c, $m) = ($1, $2);
      next if $c =~ /^Some/;
      if (!$java{$c}) { report($f, $n, "no such test class: $c") unless sibling($c) }
      elsif (defined $m && !javacontains($c, $m)) { report($f, $n, "no such test method: $c.$m") }
    }
  }

  next unless $isdoc;
  # Checks 2 and 4 on comment-joined text: strip line prefixes, join with spaces, map offsets back to lines.
  my ($j, @off) = ('');
  for my $i (0 .. $#lines) {
    (my $s = $lines[$i]) =~ s{^\s*(?:/\*\*?|\*/|\*|//+|>)?\s*}{};
    push @off, length $j; $j .= $s . ' ';
  }
  my $line = sub {
    my $o = shift; my ($lo, $hi) = (0, $#off);
    while ($lo < $hi) {
      my $m = int(($lo + $hi + 1) / 2);
      if ($off[$m] <= $o) { $lo = $m } else { $hi = $m - 1 }
    }
    $lo + 1;
  };
  while ($j =~ m{design/([\w-]+)\.md`?\}?,\s+"([^"]+)"}g) {
    my ($note, $t, $at) = ("design/$1.md", norm($2), $-[0]);
    next unless $titles{$note};
    report($f, $line->($at), qq{no heading or lead-in "$t" in $note}) unless prefix_of($t, $titles{$note});
  }
  while ($j =~ m~UPSTREAM\.md[`}]*'s entry\s+"([^"]+)"~g) {
    my ($t, $at) = (norm($1), $-[0]);
    report($f, $line->($at), qq{no UPSTREAM.md entry "$t"}) unless prefix_of($t, \@up);
  }
}

# Check 6.
for my $f (grep { m{^design/[^/]+\.md$} } @files) {
  report($f, 1, "over the ~20 KB budget (" . (-s $f) . " bytes)") if -s $f > 20480;
}
if (@out) { print "$_\n" for @out; exit 1 }
