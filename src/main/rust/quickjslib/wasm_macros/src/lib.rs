use proc_macro::TokenStream;
use quote::{format_ident, quote};
use regex::Regex;
use syn::{parse_macro_input, FnArg, ItemFn, Pat, ReturnType};

#[proc_macro_attribute]
pub fn wasm_export(_attr: TokenStream, item: TokenStream) -> TokenStream {
    let input_fn = parse_macro_input!(item as ItemFn);
    let fn_name = &input_fn.sig.ident;
    let wrapper_name = format_ident!("{}_wasm", fn_name);
    let _vis = &input_fn.vis;

    let mut wrapper_args = Vec::new();
    let mut conversions = Vec::new();
    let mut call_args = Vec::new();

    let box_regex = Regex::new(r"Box < ([\s\w<>']*) >").unwrap();
    let persistent_regex = Regex::new(r"& (Persistent < [\w\s<>']* >)").unwrap();

    for arg in &input_fn.sig.inputs {
        if let FnArg::Typed(pat_type) = arg {
            let arg_name = if let Pat::Ident(id) = &*pat_type.pat {
                &id.ident
            } else {
                continue;
            };
            let arg_type = &pat_type.ty;
            let type_str = quote!(#arg_type).to_string();

            if ["i32", "u32", "i64", "u64", "f32", "f64"].contains(&type_str.as_str()) {
                wrapper_args.push(quote!(#arg_name: #arg_type));
                call_args.push(quote!(#arg_name));
            } else if type_str == "& Runtime" {
                wrapper_args.push(quote!(#arg_name: u64));
                call_args.push(quote!(&#arg_name));

                conversions.push(quote! {
                    let #arg_name = unsafe { &*(#arg_name as *mut Runtime) };
                });
            } else if type_str == "& Context" {
                wrapper_args.push(quote!(#arg_name: u64));
                call_args.push(quote!(&#arg_name));

                conversions.push(quote! {
                    let #arg_name = unsafe { &*(#arg_name as *mut Context) };
                });
            } else if type_str == "& Ctx < '_ >" {
                wrapper_args.push(quote!(#arg_name: u64));
                let context_name = format_ident!("{}_context", arg_name);
                let org_fn = format_ident!("{}_org", fn_name);

                let mut call_args_without_context = Vec::new();
                let mut call_args_with_context = Vec::new();
                for arg in &input_fn.sig.inputs {
                    if let FnArg::Typed(pat_type) = arg {
                        let arg_name = if let Pat::Ident(id) = &*pat_type.pat {
                            &id.ident
                        } else {
                            continue;
                        };
                        let arg_type = &pat_type.ty;
                        if quote!(#arg_type).to_string() != "& Ctx < '_ >" {
                            call_args_without_context.push(quote!(#arg_name));
                            call_args_with_context.push(quote!(#arg_name));
                        } else {
                            call_args_with_context.push(quote!(&#arg_name));
                        }
                    }
                }

                conversions.push(quote! {
                    let #context_name = unsafe { &*(#arg_name as *mut Context) };

                    let #org_fn = #fn_name;
                    let #fn_name = | #(#call_args_without_context),* | {
                        use crate::from_error::FromError;
                        #context_name.with(|#arg_name| match #org_fn( #(#call_args_with_context),* ) {
                            Ok(value) => value,
                            Err(err) => FromError::from_err(&#arg_name, err),
                        })
                    };

                });
            } else if let Some(caps) = box_regex.captures(&type_str) {
                wrapper_args.push(quote!(#arg_name: u64));
                call_args.push(quote!(#arg_name));
                // Get the string content inside the Box < ... >
                let inner_type_string = &caps[1];
                let inner_type: syn::Type =
                    syn::parse_str(inner_type_string).expect("Failed to parse inner type of Box");

                conversions.push(quote! {
                    let #arg_name = unsafe { Box::from_raw(#arg_name as *mut #inner_type) };
                });
            } else if let Some(caps) = persistent_regex.captures(&type_str) {
                wrapper_args.push(quote!(#arg_name: u64));
                call_args.push(quote!(#arg_name));
                let inner_type_string = &caps[1];
                let inner_type: syn::Type = syn::parse_str(inner_type_string)
                    .expect("Failed to parse inner type Persistent");

                conversions.push(quote! {
                    let #arg_name = unsafe { &*(#arg_name as *mut #inner_type) };
                });
            } else {
                let ptr_name = format_ident!("{}_ptr", arg_name);
                let len_name = format_ident!("{}_len", arg_name);
                wrapper_args.push(quote!(#ptr_name: *mut u8, #len_name: usize));

                if type_str == "String" {
                    conversions.push(quote! {
                        let #arg_name = unsafe { String::from_raw_parts(#ptr_name, #len_name, #len_name) };
                    });
                } else {
                    conversions.push(quote! {
                        let slice = unsafe { std::slice::from_raw_parts(#ptr_name, #len_name) };
                        let #arg_name: #arg_type = match rmp_serde::from_slice(slice) {
                            Ok(result) => result,
                            Err(e) => {
                                log::error!(
                                    "MsgPack decode of argument {} (type {}) from java context failed: {}",
                                    stringify!(#arg_name),
                                    stringify!(#arg_type),
                                    e
                                );
                                panic!("MsgPack decode of argument {} (type {}) from java context failed: {}", stringify!(#arg_name), stringify!(#arg_type), e);
                            }
                        };
                        log::debug!("Converted argument {} -> {:?}", stringify!(#arg_name), #arg_name);
                    });
                }
                call_args.push(quote!(#arg_name));
            }
        }
    }

    let return_handling = match &input_fn.sig.output {
        ReturnType::Default => quote! {
            #[doc = "No return type"]
            let _result = #fn_name(#(#call_args),*);
            0
        },
        ReturnType::Type(_, ty) => {
            // let type_str = quote!(#ty).to_string();
            quote! {
                #[doc = concat!(" target type: ", stringify!(#ty))]
                let result = #fn_name(#(#call_args),*);
                use crate::into_wasm_result::IntoWasmResult;
                result.into_wasm()
            }
        }
    };

    let expanded = quote! {
        #input_fn

        #[no_mangle]
        pub extern "C" fn #wrapper_name(#(#wrapper_args),*) -> u64 {
            #(#conversions)*
            #return_handling
        }
    };

    TokenStream::from(expanded)
}
