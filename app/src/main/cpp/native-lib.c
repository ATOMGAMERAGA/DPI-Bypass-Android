#include <string.h>
#include <netdb.h>
#include <sys/resource.h>

#include <jni.h>
#include <android/log.h>

#include "byedpi/error.h"
#include "byedpi/proxy.h"
#include "byedpi/params.h"
#include "byedpi/packets.h"
#include "main.h"
#include "utils.h"

const enum demode DESYNC_METHODS[] = {
    DESYNC_NONE,
    DESYNC_SPLIT,
    DESYNC_DISORDER,
    DESYNC_FAKE,
    DESYNC_OOB,
    DESYNC_DISOOB,
};

enum hosts_mode {
    HOSTS_DISABLE,
    HOSTS_BLACKLIST,
    HOSTS_WHITELIST,
};

/*
 * Süreç başına dosya tanıtıcı sınırı. ByeDPI ve hev-socks5-tunnel aynı süreçte
 * çalışır; her TCP bağlantısı ~3, her UDP oturumu (oyun, DNS) ~5 tanıtıcı
 * harcar. Sınır dolarsa ByeDPI'ın accept() çağrısı EMFILE ile döner ve olay
 * döngüsü TAMAMEN kapanır: bütün uygulamaların (ör. Roblox oyunu) bağlantısı
 * bir anda kopar. Bu yüzden yumuşak sınır, izin verilen en yükseğe çekilir.
 */
static rlim_t raise_nofile_limit(void) {
    struct rlimit rl;
    if (getrlimit(RLIMIT_NOFILE, &rl) < 0) {
        return 1024;
    }
    if (rl.rlim_cur < rl.rlim_max) {
        rlim_t old = rl.rlim_cur;
        rl.rlim_cur = rl.rlim_max;
        if (setrlimit(RLIMIT_NOFILE, &rl) < 0) {
            rl.rlim_cur = old;
        }
    }
    return rl.rlim_cur == RLIM_INFINITY ? 65536 : rl.rlim_cur;
}

/*
 * Varsayılan 512 bağlantılık havuz, "tüm uygulamalar" kipinde DNS sorguları
 * (her biri ayrı UDP oturumu, ~60 sn yaşar) ve oyunların çok sayıda HTTPS
 * isteğiyle kolayca dolar; dolunca yeni bağlantılar reddedilir. Havuz, tanıtıcı
 * sınırının taşmayacağı kadar büyütülür. Kullanıcı -c ile kendisi verdiyse
 * dokunulmaz.
 */
static void tune_max_open(rlim_t nofile) {
    if (params.max_open != default_params.max_open) {
        return;
    }
    // ByeDPI ~2, tünel ~1 tanıtıcı/yuva; JVM ve diğerleri için 512 pay.
    long fit = ((long) nofile - 512) / 3;
    if (fit > 4096) fit = 4096;
    if (fit < 128) fit = 128;
    params.max_open = (int) fit;
}

JNIEXPORT jint JNI_OnLoad(
        __attribute__((unused)) JavaVM *vm,
        __attribute__((unused)) void *reserved) {
    default_params = params;
    raise_nofile_limit();
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_net_atom_dpibypass_engine_ByeDpiProxy_jniCreateSocketWithCommandLine(
        JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jobjectArray args) {
    int argc = (*env)->GetArrayLength(env, args);
    char *argv[argc];
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        argv[i] = strdup(arg_str);
        (*env)->ReleaseStringUTFChars(env, arg, arg_str);
    }

    int res = parse_args(argc, argv);
    if (res < 0) {
        uniperror("parse_args");
        return -1;
    }
    tune_max_open(raise_nofile_limit());
    LOG(LOG_S, "max_open: %d", params.max_open);

    int fd = listen_socket((struct sockaddr_ina *)&params.laddr);
    if (fd < 0) {
        uniperror("listen_socket");
        // Yarım kalan argümanlar bir sonraki denemeye taşınmasın.
        reset_params();
        return -1;
    }
    LOG(LOG_S, "listen_socket, fd: %d", fd);

    return fd;
}

JNIEXPORT jint JNICALL
Java_net_atom_dpibypass_engine_ByeDpiProxy_jniCreateSocket(
        JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jstring ip,
        jint port,
        jint max_connections,
        jint buffer_size,
        jint default_ttl,
        jboolean custom_ttl,
        jboolean no_domain,
        jboolean desync_http,
        jboolean desync_https,
        jboolean desync_udp,
        jint desync_method,
        jint split_position,
        jboolean split_at_host,
        jint fake_ttl,
        jstring fake_sni,
        jbyte custom_oob_char,
        jboolean host_mixed_case,
        jboolean domain_mixed_case,
        jboolean host_remove_spaces,
        jboolean tls_record_split,
        jint tls_record_split_position,
        jboolean tls_record_split_at_sni,
        jint hosts_mode,
        jstring hosts,
        jboolean tfo,
        jint udp_fake_count,
        jboolean drop_sack,
        jint fake_offset) {
    struct sockaddr_ina s;

    const char *address = (*env)->GetStringUTFChars(env, ip, 0);
    int res = get_addr(address, &s);
    (*env)->ReleaseStringUTFChars(env, ip, address);
    if (res < 0) {
        uniperror("get_addr");
        return -1;
    }

    s.in.sin_port = htons(port);

    params.max_open = max_connections;
    params.bfsize = buffer_size;
    params.resolve = !no_domain;
    params.tfo = tfo;

    if (custom_ttl) {
        params.def_ttl = default_ttl;
        params.custom_ttl = 1;
    }

    if (!params.def_ttl) {
        if ((params.def_ttl = get_default_ttl()) < 1) {
            uniperror("get_default_ttl");
            reset_params();
            return -1;
        }
    }

    if (hosts_mode == HOSTS_WHITELIST) {
        struct desync_params *dp = add(
                (void *) &params.dp,
                &params.dp_count,
                sizeof(struct desync_params)
        );
        if (!dp) {
            uniperror("add");
            reset_params();
            return -1;
        }

        const char *str = (*env)->GetStringUTFChars(env, hosts, 0);
        dp->file_ptr = data_from_str(str, &dp->file_size);
        (*env)->ReleaseStringUTFChars(env, hosts, str);
        dp->hosts = parse_hosts(dp->file_ptr, dp->file_size);
        if (!dp->hosts) {
            perror("parse_hosts");
            clear_params();
            return -1;
        }
    }

    struct desync_params *dp = add(
            (void *) &params.dp,
            &params.dp_count,
            sizeof(struct desync_params)
    );
    if (!dp) {
        uniperror("add");
        reset_params();
        return -1;
    }

    if (hosts_mode == HOSTS_BLACKLIST) {
        const char *str = (*env)->GetStringUTFChars(env, hosts, 0);
        dp->file_ptr = data_from_str(str, &dp->file_size);
        (*env)->ReleaseStringUTFChars(env, hosts, str);
        dp->hosts = parse_hosts(dp->file_ptr, dp->file_size);
        if (!dp->hosts) {
            perror("parse_hosts");
            clear_params();
            return -1;
        }
    }

    dp->ttl = fake_ttl;
    dp->udp_fake_count = udp_fake_count;
    dp->drop_sack = drop_sack;
    dp->proto =
            IS_HTTP * desync_http |
            IS_HTTPS * desync_https |
            IS_UDP * desync_udp;
    dp->mod_http =
            MH_HMIX * host_mixed_case |
            MH_DMIX * domain_mixed_case |
            MH_SPACE * host_remove_spaces;

    struct part *part = add(
            (void *) &dp->parts,
            &dp->parts_n,
            sizeof(struct part)
    );
    if (!part) {
        uniperror("add");
        reset_params();
        return -1;
    }

    enum demode mode = DESYNC_METHODS[desync_method];

    int offset_flag = dp->proto || desync_https ? OFFSET_SNI : OFFSET_HOST;

    part->flag = split_at_host ? offset_flag : 0;
    part->pos = split_position;
    part->m = mode;

    if (tls_record_split) {
        struct part *tlsrec_part = add(
                (void *) &dp->tlsrec,
                &dp->tlsrec_n,
                sizeof(struct part)
        );

        if (!tlsrec_part) {
            uniperror("add");
            reset_params();
            return -1;
        }

        tlsrec_part->flag = tls_record_split_at_sni ? offset_flag : 0;
        tlsrec_part->pos = tls_record_split_position;
    }

    if (mode == DESYNC_FAKE) {
        dp->fake_offset = fake_offset;

        const char *sni = (*env)->GetStringUTFChars(env, fake_sni, 0);
        LOG(LOG_S, "fake_sni: %s", sni);
        res = change_tls_sni(sni, fake_tls.data, fake_tls.size);
        (*env)->ReleaseStringUTFChars(env, fake_sni, sni);
        if (res) {
            fprintf(stderr, "error chsni\n");
            return -1;
        }
    }

    if (mode == DESYNC_OOB) {
        dp->oob_char[0] = custom_oob_char;
        dp->oob_char[1] = 1;
    }

    if (dp->proto) {
        dp = add((void *)&params.dp,
                 &params.dp_count, sizeof(struct desync_params));
        if (!dp) {
            uniperror("add");
            clear_params();
            return -1;
        }
    }

    params.mempool = mem_pool(0);
    if (!params.mempool) {
        uniperror("mem_pool");
        clear_params();
        return -1;
    }

    int fd = listen_socket(&s);
    if (fd < 0) {
        uniperror("listen_socket");
        return -1;
    }
    LOG(LOG_S, "listen_socket, fd: %d", fd);

    return fd;
}

JNIEXPORT jint JNICALL
Java_net_atom_dpibypass_engine_ByeDpiProxy_jniStartProxy(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jint fd) {
    LOG(LOG_S, "start_proxy, fd: %d", fd);
    NOT_EXIT = 1;
    if (event_loop(fd) < 0) {
        uniperror("event_loop");
        return get_e();
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_net_atom_dpibypass_engine_ByeDpiProxy_jniStopProxy(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jint fd) {
    LOG(LOG_S, "stop_proxy, fd: %d", fd);

    // fd < 0: olay döngüsü kendiliğinden bitti ve dinleme soketini zaten
    // kapattı. O numara artık başka bir sokete (ör. tünelin) ait olabilir;
    // shutdown edilmez, yalnızca parametreler sıfırlanır.
    int res = fd >= 0 ? shutdown(fd, SHUT_RDWR) : 0;
    reset_params();

    if (res < 0) {
        uniperror("shutdown");
        return get_e();
    }
    return 0;
}