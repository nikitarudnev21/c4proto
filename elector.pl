
use strict;
use JSON::XS;

sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
my $single_or_undef = sub{ @_==1 ? $_[0] : undef };
my $group = sub{ my %r; push @{$r{$$_[0]}||=[]}, $$_[1] for @_; (sub{@{$r{$_[0]}||[]}},[sort keys %r]) };
my $json = sub{ JSON::XS->new->ascii(1)->canonical(1)->pretty(1) };
my $forever = sub{ my($f,@state)=@_; @state = &$f(@state) while 1; };

my @tasks;
my $get_handler = sub{ for my $v(@_){$v eq $$_[0] and return $$_[1] for @tasks} die };

###

push @tasks, [kubectl=>sub{
    &$exec('kubectl', 'proxy', '--port=8080', '--disable-filter=true');
}];

push @tasks, [elector=>sub{
    my $ns = `cat /var/run/secrets/kubernetes.io/serviceaccount/namespace`=~/(\w+)/ ? "$1" : die;
    &$forever(sub{
        my ($was_container_ids_by_app) = @_;
        my $pods_data = syf("kubectl -n $ns get pods -l c4rolling=v1 -o json");
        #
        my ($pods_by_app,$apps) = &$group(map{ my $pod = $_;
            my $rolling = $$pod{metadata}{annotations}{c4rolling} || die;
            my $app = $$pod{metadata}{labels}{app} || die;
            my $container_status = &$single_or_undef(@{$$pod{status}{containerStatuses}||die});
            my $container_name = $container_status && $$container_status{name};
            my $id = $container_status && $$container_status{containerID};
            my $pod_name = $$pod{metadata}{name} || die;
            my $exec = $container_name && "kubectl -n $ns exec $pod_name -c $container_name -- ";
            my $get = $exec && "$exec sh -c ls $rolling";
            my $set = $exec && "$exec sh -c '>$rolling/c4is-master'";
            my $running = $exec && ($$container_status{state}||{})->{running} ? 1:0;
            $id && $container_name ? [$app,{
                container_id => $id, exec_get => $get, exec_set => $set, is_running=>$running,
            }] : ()
        } @{&$json()->decode($pods_data)->{items}||die});
        my $container_ids_by_app = {map{
            ($_ => join " ", sort map{"$$_{container_id}/$$_{is_running}"} &$pods_by_app($_))
        }@$apps};
        my @invalidate_apps = grep{ $$container_ids_by_app{$_} ne $$was_container_ids_by_app{$_} } @$apps;
        #
        for my $app(@invalidate_apps){
            my @pods = map{
              my $r = syf($$_{exec_get});
              my $is_master = $r=~/\bc4is-master\b/ ? 1:0;
              my $is_ready = $r=~/\bc4is-ready\b/ ? 1:0;
              print "is ready: $is_ready; is master: $is_master\n";
              +{ %$_, is_ready=>$is_ready, is_master=>$is_master }
            } grep{ $$_{is_running} } &$pods_by_app($app);
            my @ready_pods = grep{$$_{is_ready}} @pods;
            my @master_pods = grep{$$_{is_master}} @pods;
            next if @master_pods || !@pods; #todo Terminating may not report as master; we should remember set or ignore transient states?
            syf((@ready_pods,@pods)[0]{exec_set}||die);
        }
        sleep 1;
        ($container_ids_by_app);
    },+{});
}];

###

my($cmd,@args)=@ARGV;
&$get_handler($cmd,'def')->(@args);