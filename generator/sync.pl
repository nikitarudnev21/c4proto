
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my $to_rel = sub{
    my($pre,$fn)=@_;
    $pre eq substr $fn, 0, length $pre or die;
    substr $fn, length $pre;
};

my $find = sub{
    my($pre,$from,$prune)=@_;
    my $cmd = "${pre}find $from";
    my $prune_str = join'', map{" -o -name '$_' -prune"} @$prune;
    my %prune_h = map{($_=>1)} @$prune;
    map{ &$to_rel($from,$_)=~m{(.+/|)([^/]+)\n} && !$prune_h{$2} ? "$1$2" : () }
        sort `$cmd -type f$prune_str`;
};

my $sync = sub{
    my ($list_fn,$ssh,$from,$from_fns,$to,$to_fns,$to_rm_f) = @_;
    my %keep = map{($_=>1)} @$from_fns;
    my $to_rm = join " ", grep{!$keep{$_}} @$to_fns;
    &$put_text($list_fn, join "", map{"$_\n"} @$from_fns);
    sy("rsync -e '$ssh' -av --files-from=$list_fn $from/ $to") if @$from_fns;
    sy(&$to_rm_f($to_rm)) if $to_rm;
};

my $filter = sub{grep{m{\bc4gen\b}}@_};
my $prune = [qw(target .git .idea)];

my $port = ($ENV{C4BUILD_PORT}-0) || die;
my $clean = $ENV{C4BUILD_CLEAN}-0;
my $cmd = $ENV{C4BUILD_CMD};
my $dir = $ARGV[0] || die;
my $remote_dir = $ARGV[1] || die;

my $ssh = "ssh -p$port";
my $remote = "c4\@127.0.0.1:$remote_dir";
my $remote_pre = "$ssh c4\@127.0.0.1 ";
my $list_fn = "$dir/target/c4sync";

$clean ?
    sy("$remote_pre 'mkdir -p $remote_dir && rm -r $remote_dir && mkdir $remote_dir'"):
    sy("$remote_pre 'mkdir -p $remote_dir'");
mkdir "$dir/target";
my @local_fns = &$find("","$dir/",$prune);
&$sync($list_fn,$ssh,
    $dir,[@local_fns],
    $remote,[&$find($remote_pre,"$remote_dir/",$prune)],
    sub{"$remote_pre 'cd $remote_dir && rm $_[0]'"}
);
sy("$remote_pre '$cmd'") if $cmd;
&$sync($list_fn,$ssh,
    $remote,[&$filter(&$find($remote_pre,"$remote_dir/",$prune))],
    $dir,[&$filter(@local_fns)],
    sub{"cd $dir && rm $_[0]"}
) if $cmd;
