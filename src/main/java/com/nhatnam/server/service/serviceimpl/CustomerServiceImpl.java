package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.request.CreateCustomerRequest;
import com.nhatnam.server.dto.request.UpdateCustomerRequest;
import com.nhatnam.server.dto.response.CustomerResponse;
import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.CustomerAddress;
import com.nhatnam.server.repository.CustomerAddressRepository;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.service.CustomerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerAddressRepository addressRepository;

    @Override
    @Transactional
    public CustomerResponse createCustomer(CreateCustomerRequest request) {
        long now = System.currentTimeMillis();

        // Kiểm tra số điện thoại đã tồn tại chưa
        if (customerRepository.existsByPhone(request.getPhone())) {
            throw new RuntimeException("Số điện thoại đã được đăng ký: " + request.getPhone());
        }

        // Tạo khách hàng mới
        Customer customer = Customer.builder()
                .phone(request.getPhone())
                .name(request.getName())
                .email(request.getEmail())
                .discountRate(request.getDiscountRate() != null ? request.getDiscountRate() : 0)
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .addresses(request.getAddresses() != null ?
                        request.getAddresses().stream()
                                .map(addrReq -> CustomerAddress.builder()
                                        .address(addrReq.getAddress())
                                        .isDefault(addrReq.isDefault())
                                        .build())
                                .collect(Collectors.toList())
                        : new java.util.ArrayList<>())
                .build();

        // Gán customer cho từng address
        if (customer.getAddresses() != null) {
            customer.getAddresses().forEach(addr -> addr.setCustomer(customer));
        }

        Customer savedCustomer = customerRepository.save(customer);

        return mapToResponse(savedCustomer);
    }

    @Override
    @Transactional
    public CustomerResponse updateCustomer(Long id, UpdateCustomerRequest request) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy khách hàng với ID: " + id));

        // Số điện thoại không được thay đổi
        if (!customer.getPhone().equals(request.getPhone())) {
            throw new RuntimeException("Không thể thay đổi số điện thoại");
        }

        // Cập nhật thông tin cơ bản
        customer.setName(request.getName());
        customer.setEmail(request.getEmail());
        customer.setDiscountRate(request.getDiscountRate() != null ? request.getDiscountRate() : 0);
        customer.setUpdatedAt(System.currentTimeMillis());

        // Xử lý địa chỉ - KHÔNG dùng setAddresses() vì sẽ mất Hibernate reference
        if (request.getAddresses() != null) {
            customer.getAddresses().clear(); // ← xóa các item cũ trong collection gốc

            for (var addrReq : request.getAddresses()) {
                customer.getAddresses().add(CustomerAddress.builder()
                        .customer(customer)
                        .address(addrReq.getAddress())
                        .isDefault(addrReq.isDefault())
                        .build());
            }
        }

        Customer updatedCustomer = customerRepository.save(customer);

        return mapToResponse(updatedCustomer);
    }

    @Override
    @Transactional
    public void deleteCustomer(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy khách hàng với ID: " + id));

        customer.setIsActive(false);
        customer.setUpdatedAt(System.currentTimeMillis());

        // Soft delete địa chỉ
        if (customer.getAddresses() != null) {
            customer.getAddresses().forEach(addr -> addr.setIsActive(false));
        }

        customerRepository.save(customer);
    }

    @Override
    public CustomerResponse getCustomerById(Long id) {
        Customer customer = customerRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy khách hàng với ID: " + id));
        return mapToResponse(customer);
    }

    @Override
    public CustomerResponse getCustomerByPhone(String phone) {
        Customer customer = customerRepository.findByPhone(phone)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy khách hàng với số điện thoại: " + phone));

        if (!customer.getIsActive()) {
            throw new RuntimeException("Khách hàng đã bị vô hiệu hóa");
        }

        return mapToResponse(customer);
    }

    @Override
    public List<CustomerResponse> getAllActiveCustomers() {
        return customerRepository.findByIsActiveTrue().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public Page<CustomerResponse> searchCustomers(String keyword, Pageable pageable) {
        return customerRepository.searchCustomers(keyword, pageable)
                .map(this::mapToResponse);
    }

    @Override
    public List<CustomerResponse> findCustomersByName(String name) {
        return customerRepository.findByNameContainingIgnoreCase(name).stream()
                .filter(Customer::getIsActive)
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<CustomerResponse> findCustomersByPhone(String phone) {
        return customerRepository.findByPhoneContaining(phone).stream()
                .filter(Customer::getIsActive)
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private CustomerResponse mapToResponse(Customer customer) {
        List<CustomerResponse.AddressResponse> addressResponses =
                customer.getAddresses().stream()
                        .filter(CustomerAddress::getIsActive)
                        .map(addr -> CustomerResponse.AddressResponse.builder()
                                .id(addr.getId())
                                .address(addr.getAddress())
                                .isDefault(addr.getIsDefault())
                                .build())
                        .collect(Collectors.toList());

        return CustomerResponse.builder()
                .id(customer.getId())
                .phone(customer.getPhone())
                .name(customer.getName())
                .email(customer.getEmail())
                .discountRate(customer.getDiscountRate())
                .isActive(customer.getIsActive())
                .createdAt(customer.getCreatedAt())
                .updatedAt(customer.getUpdatedAt())
                .addresses(addressResponses)
                .build();
    }
}