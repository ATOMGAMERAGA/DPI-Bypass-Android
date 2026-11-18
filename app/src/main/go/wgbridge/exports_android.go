//go:build android

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"sync"
	"unsafe"
)

var (
	mu      sync.Mutex
	current *Bridge
)

//export wgbridgeStart
func wgbridgeStart(uapi, addresses, dns *C.char, mtu C.int, listen *C.char) *C.char {
	mu.Lock()
	defer mu.Unlock()
	if current != nil {
		current.Close()
		current = nil
	}
	cfg, err := ParseConfig(C.GoString(uapi), C.GoString(addresses), C.GoString(dns), int(mtu), C.GoString(listen))
	if err != nil {
		return C.CString(err.Error())
	}
	b, err := Start(cfg)
	if err != nil {
		return C.CString(err.Error())
	}
	current = b
	return nil
}

//export wgbridgeStop
func wgbridgeStop() {
	mu.Lock()
	defer mu.Unlock()
	if current != nil {
		current.Close()
		current = nil
	}
}

//export wgbridgeStatus
func wgbridgeStatus() *C.char {
	mu.Lock()
	defer mu.Unlock()
	if current == nil {
		return C.CString("")
	}
	return C.CString(current.Status())
}

//export wgbridgeFree
func wgbridgeFree(p *C.char) { C.free(unsafe.Pointer(p)) }
